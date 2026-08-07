# StreamLine: Distributed Messaging Engine

StreamLine is a high-throughput WebSocket messaging server built with Java 21 and Spring Boot,
paired with two benchmark clients used to measure its throughput and tail latency.

## Architecture

```
Server/
├── src/main/java/server/
│   ├── api/            # REST controller, response records, error handling, OpenAPI
│   ├── configure/      # WebSocket handler, rate limiter, metrics, async pool, probes
│   ├── model/          # ChatMessage entity
│   ├── repository/     # MessageRepository (Spring Data JPA)
│   └── service/        # ChatService (async, transactional persistence)
├── src/main/resources/
│   └── static/         # Browser chat client
├── src/test/java/      # Unit and integration tests
├── Dockerfile
└── pom.xml

bench-common/           # Message model, config, backoff and generator shared by both clients
load-tester/            # Throughput benchmark (warm-up + main phase)
latency-analyzer/       # Per-message latency capture, writes CSV reports
```

The two benchmark clients share `bench-common`, which they resolve from the
local Maven repository. `make` installs it automatically; building a client
directly needs `mvn install -f bench-common/pom.xml` first.

**Key design decisions**

1. **Writes are off the hot path.** `ChatService.saveMessage` is `@Async` and `@Transactional`,
   so database I/O never blocks the WebSocket thread that is acknowledging a client.
2. **The write pool is bounded.** Persistence runs on a dedicated pool with a fixed queue and a
   caller-runs rejection policy, so traffic that outruns the database creates visible back
   pressure instead of unbounded heap growth.
3. **Room state is lock-free.** `ConcurrentHashMap` plus `CopyOnWriteArrayList` keeps room
   membership safe under concurrent joins and leaves without a global lock.
4. **History replay on join.** A joining client receives the room's last 50 messages before its
   join acknowledgement, so it has context immediately.
5. **Only chat text is durable.** `JOIN` and `LEAVE` are connection control frames, so they are
   not stored: persisting them replayed "alice: Joining" back as room history, and they are
   roughly 10% of benchmark traffic.

## Protocol

Connect to `ws://<host>:8080/chat/{roomId}` and send JSON frames:

```json
{
  "userId": 42,
  "username": "alice",
  "message": "hello",
  "timestamp": "2026-08-04T10:00:00Z",
  "messageType": "TEXT"
}
```

| Field | Rules |
| --- | --- |
| `userId` | integer, 1–100000 |
| `username` | 3–20 characters, alphanumeric only |
| `message` | 1–500 characters |
| `timestamp` | required, ISO-8601 |
| `messageType` | `JOIN`, `TEXT`, or `LEAVE` |

A client must `JOIN` before it may send `TEXT` or `LEAVE`. Every frame is answered with:

```json
{ "status": "OK", "serverTimestamp": "...", "message": "alice: hello" }
```

| `status` | Meaning |
| --- | --- |
| `OK` | Acknowledgement to the sender |
| `BROADCAST` | A message from another member of the room |
| `HISTORY` | A replayed past message, sent oldest first on join |
| `PRESENCE` | Comma-separated list of everyone currently in the room |
| `ERROR` | Validation failure or protocol violation |

A `PRESENCE` frame is pushed to every member whenever someone joins, leaves, or
disconnects, so clients never poll for the member list.

## Browser client

Open `http://localhost:8080/` once the server is running. The bundled client
joins a room, streams messages live, and shows each frame's status, so the
server can be exercised without any extra tooling.

## HTTP endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /` | Browser chat client |
| `GET /health` | Liveness probe; does no I/O, returns `{"status":"RUNNING"}` |
| `GET /ready` | Readiness probe; checks the database, 503 when not ready |
| `GET /stats` | Live room count, joined sessions, and per-room occupancy |
| `GET /api/rooms` | Rooms with at least one member, with stored message counts |
| `GET /api/rooms/{roomId}` | One room: members present, sessions, stored messages |
| `GET /api/rooms/{roomId}/messages` | Paginated room history, newest first |
| `GET /api/rooms/{roomId}/search` | Search a room's history by text and optionally author |
| `GET /swagger-ui.html` | Interactive API documentation |
| `GET /v3/api-docs` | OpenAPI document |
| `GET /actuator/health` | Actuator health, including database status |
| `GET /actuator/metrics` | JVM, HTTP and chat metrics |
| `GET /actuator/prometheus` | The same metrics in Prometheus format |

Use `/health` for liveness and `/ready` for load-balancer routing: a server whose
database is unreachable is still alive but cannot persist anything, so only
`/ready` reports it as unavailable.

### Who is in a room

```bash
curl http://localhost:8080/api/rooms/1
```

```json
{ "roomId": "1", "members": ["alice", "bob"], "sessions": 2, "storedMessages": 96 }
```

`members` is de-duplicated, so a user connected twice appears once; `sessions`
counts open sockets.

### Reading history

```bash
curl 'http://localhost:8080/api/rooms/1/messages?page=0&size=25'
```

```json
{
  "roomId": "1",
  "messages": [
    { "username": "alice", "message": "hello", "timestamp": "2026-08-06T10:00:00Z", "roomId": "1" }
  ],
  "page": 0,
  "size": 25,
  "totalMessages": 96,
  "totalPages": 4,
  "hasMore": true
}
```

`size` is clamped to 1..200 rather than rejected. Failures return a consistent
body:

```json
{ "status": 400, "error": "Bad Request", "message": "page must not be negative, got -1",
  "timestamp": "2026-08-06T10:00:00Z" }
```

## Access control

Disabled by default, so nothing changes until it is switched on:

```bash
AUTH_ENABLED=true AUTH_TOKEN=a-long-random-secret mvn spring-boot:run
```

With it enabled, every `/api` call, the OpenAPI document, and every WebSocket
handshake must present the token. Startup fails if the token is missing or
shorter than 16 characters, rather than running while believing it is protected.

| Where | How to present it |
| --- | --- |
| REST | `X-Streamline-Token: <token>` header, or `?token=<token>` |
| WebSocket | `?token=<token>` on the handshake URL |
| Benchmark clients | `-Dstreamline.token=<token>` or `STREAMLINE_TOKEN` |
| Browser client | The token field in the header bar |

`/health` and `/ready` deliberately stay open so load balancer probes keep
working; `/actuator` is protected unless `AUTH_PROTECT_ACTUATOR=false`.

## Storage

Schema is managed by Flyway (`Server/src/main/resources/db/migration`), and
Hibernate runs with `ddl-auto=validate`, so the server refuses to start if the
entities and the migrations have drifted apart.

The default is embedded H2. For Postgres:

```bash
SPRING_PROFILES_ACTIVE=postgres DB_URL=jdbc:postgresql://localhost:5432/streamline \
  DB_USERNAME=streamline DB_PASSWORD=streamline mvn spring-boot:run

# or the whole stack, database included
docker compose --profile postgres up
```

## Running the server

```bash
cd Server
mvn spring-boot:run
```

Or with Docker:

```bash
docker build -t streamline-server Server
docker run -p 8080:8080 -v streamline-data:/app/data streamline-server
```

### Configuration

Every setting has a working default; override through environment variables.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP/WebSocket port |
| `DB_URL` | `jdbc:h2:file:./data/streamline` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / empty | Database credentials |
| `DB_POOL_SIZE` | `32` | Hikari connection pool size |
| `LOG_LEVEL` | `INFO` | Log level for `server.*` |
| `BROADCAST_ENABLED` | `true` | Fan messages out to other room members |
| `PERSIST_CORE_POOL` | `8` | Core persistence threads |
| `PERSIST_MAX_POOL` | `32` | Max persistence threads |
| `PERSIST_QUEUE_CAPACITY` | `10000` | Queued writes before back pressure |
| `AUTH_ENABLED` | `false` | Require a token on the API and chat handshake |
| `AUTH_TOKEN` | empty | The shared secret; at least 16 characters when enabled |
| `AUTH_HEADER` | `X-Streamline-Token` | Header carrying the token |
| `AUTH_QUERY_PARAM` | `token` | Query parameter accepted instead of the header |
| `AUTH_PROTECT_ACTUATOR` | `true` | Also require the token on `/actuator` |
| `RATE_LIMIT_ENABLED` | `false` | Cap how fast one session may send |
| `RATE_LIMIT_PER_SECOND` | `20` | Sustained messages per second per session |
| `RATE_LIMIT_BURST` | `40` | Burst allowance above the sustained rate |
| `RATE_LIMIT_ENABLED` | `false` | Per-session send limiting |
| `RATE_LIMIT_PER_SECOND` | `20` | Sustained messages per second per session |
| `RATE_LIMIT_BURST` | `40` | How far a session may burst above that rate |
| `WS_ALLOWED_ORIGINS` | `*` | Comma-separated origins allowed to connect |
| `WS_MAX_TEXT_BYTES` | `8192` | Maximum inbound text frame size |
| `WS_MAX_BINARY_BYTES` | `8192` | Maximum inbound binary frame size |

Set `SPRING_PROFILES_ACTIVE=json` to emit one ECS JSON object per log line
instead of human-readable console output.

### Tracing a request

Every HTTP response carries `X-Correlation-Id`. Supply the header to keep an id
started upstream, or let the server generate one. The id is attached to every
log line produced while handling that request, which is what makes it useful
with the JSON logging profile:

```bash
curl -H 'X-Correlation-Id: trace-abc-123' http://localhost:8080/api/rooms
```

### Metrics

Alongside the JVM and HTTP metrics Actuator provides:

| Metric | Meaning |
| --- | --- |
| `streamline.rooms.active` | Rooms with at least one member |
| `streamline.sessions.joined` | Sessions that have completed a JOIN |
| `streamline.messages.accepted` | Frames that passed validation and were processed |
| `streamline.messages.rejected` | Frames refused for validation or protocol reasons |
| `streamline.messages.rate_limited` | Frames dropped for exceeding the send rate |
| `streamline.broadcasts.sent` | Copies delivered to other room members |

Set `BROADCAST_ENABLED=false` when benchmarking, so measured latency reflects only the
sender's acknowledgement rather than fan-out traffic.

## Running the benchmarks

Both clients target `ws://localhost:8080` by default and take the same overrides:

```bash
cd load-tester
mvn compile exec:java -Dexec.mainClass=client.WarmUpPhase \
  -Dstreamline.url=ws://localhost:8080 \
  -Dstreamline.threads=32 \
  -Dstreamline.messages=32000
```

| Property | Environment variable | Default |
| --- | --- | --- |
| `streamline.url` | `STREAMLINE_URL` | `ws://localhost:8080` |
| `streamline.threads` | `STREAMLINE_THREADS` | per client |
| `streamline.messages` | `STREAMLINE_MESSAGES` | per client |
| `streamline.rooms` | `STREAMLINE_ROOMS` | `20` |

`latency-analyzer` additionally writes `Result/MessageMetrics.csv` and `Result/Throughput.csv`.

## Development

```bash
make help      # list every task
make verify    # every module, clean, with tests
make run       # server on :8080 with the browser client at /
make bench     # throughput benchmark against a running server
```

Always build with `clean`; see `CONTRIBUTING.md` for why.

```bash
mvn -f Server/pom.xml clean verify
```

CI runs every module's tests from a clean build and builds the Docker image on each push and
pull request.
