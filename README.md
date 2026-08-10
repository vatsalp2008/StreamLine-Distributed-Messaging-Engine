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
| `messageType` | `JOIN`, `TEXT`, `LEAVE`, or `TYPING` |

A client must `JOIN` before it may send anything else. A session is then held to
the username it joined with: a later frame claiming a different author is
refused, and a username may only appear once per room. `TYPING` is a transient
hint, never stored and never echoed back to the sender. Every frame is answered with:

```json
{ "status": "OK", "serverTimestamp": "...", "message": "alice: hello" }
```

| `status` | Meaning |
| --- | --- |
| `OK` | Acknowledgement to the sender |
| `BROADCAST` | A message from another member of the room |
| `HISTORY` | A replayed past message, sent oldest first on join |
| `PRESENCE` | Comma-separated list of everyone currently in the room |
| `DELIVERED` | The message reached storage; body is the stored id |
| `TYPING` | The username of someone composing a message |
| `ERROR` | Validation failure or protocol violation |

A `PRESENCE` frame is pushed to every member whenever someone joins, leaves, or
disconnects, so clients never poll for the member list.

### Matching a reply to a message

A sender may attach a `clientId` to any frame. The server echoes it on the `OK`
or `ERROR` that answers that frame, and on nothing else, so a client can tell
its own acknowledgement apart from fan-out traffic arriving on the same
connection. It is optional: omit it and replies simply carry no `clientId`.

```json
{"status":"OK","serverTimestamp":"...","message":"alice: hello","clientId":"m-42"}
```

### Knowing a message was stored

`OK` means the server accepted a message, not that it wrote it: persistence
happens on another thread and can still fail. With `RECEIPTS_ENABLED=true` a
second frame follows once the row exists, carrying the same `clientId` and the
stored id as its body:

```json
{"status":"DELIVERED","serverTimestamp":"...","message":"12345","clientId":"m-42"}
```

If the write fails, an `ERROR` carrying that `clientId` arrives instead, so a
sender is never left waiting for a receipt that is not coming. Receipts are off
by default because they double server-to-client frames.

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

### Per-room tokens

A single shared token opens every room. To give one room its own secret:

```properties
streamline.auth.room-tokens.private-room=a-different-long-secret
```

That room then accepts only its own token, on both the WebSocket handshake and
the room's REST endpoints; every other room continues to use the shared one.
`GET /api/rooms` stays on the shared token, since it names rooms rather than
revealing what was said in them.

## Limits

Room ids come from the connection URL, so without a cap a client can make the
server allocate rooms indefinitely. Joins beyond a cap are refused with an
`ERROR`; the room itself is never allocated.

| Variable | Default | Meaning |
| --- | --- | --- |
| `MAX_ROOMS` | `1000` | Rooms that may exist at once; `0` for unlimited |
| `MAX_MEMBERS_PER_ROOM` | `500` | Members in one room; `0` for unlimited |

`GET /stats` reports both alongside current usage, and the same figures are
exported as `streamline.rooms.*` gauges.

## Storage

Schema is managed by Flyway (`Server/src/main/resources/db/migration`), and
Hibernate runs with `ddl-auto=validate`, so the server refuses to start if the
entities and the migrations have drifted apart.

Stored messages are kept forever unless a retention window is set. `RETENTION_DAYS`
greater than zero prunes anything older on an hourly sweep; the default of `0`
keeps everything, so an existing deployment never starts discarding history
unasked.

The default is embedded H2. For Postgres:

```bash
SPRING_PROFILES_ACTIVE=postgres DB_URL=jdbc:postgresql://localhost:5432/streamline \
  DB_USERNAME=streamline DB_PASSWORD=streamline mvn spring-boot:run

# or the whole stack, database included
docker compose --profile postgres up
```

The H2 and Postgres servers live in separate compose profiles, so `--profile postgres`
starts Postgres and one server rather than two servers competing for port 8080. Use
`docker compose up server` for the H2 one.

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
| `IDENTITY_STRICT` | `true` | Hold a session to the username it joined with |
| `IDENTITY_UNIQUE` | `true` | Allow a username only once per room |
| `MAX_ROOMS` | `1000` | Cap on concurrent rooms; `0` for unlimited |
| `MAX_MEMBERS_PER_ROOM` | `500` | Cap on members per room; `0` for unlimited |
| `RETENTION_DAYS` | `0` | Days of history to keep; `0` keeps everything |
| `RECEIPTS_ENABLED` | `false` | Confirm each stored message with a `DELIVERED` frame |
| `SPRING_PROFILES_ACTIVE` | none | Set to `json` for structured logs, `postgres` for Postgres |
| `IDENTITY_STRICT` | `true` | Hold a session to the username it joined with |
| `IDENTITY_UNIQUE` | `true` | Allow a username to appear only once per room |
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
| `streamline.messages.identity_rejected` | Frames refused for claiming the wrong username |
| `streamline.typing.sent` | Typing hints delivered to room members |
| `streamline.broadcasts.sent` | Copies delivered to other room members |

`streamline.messages.identity_rejected` also rolls up into
`streamline.messages.rejected`; a rising rate on the specific counter means
someone is probing rather than mistyping.

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
| `streamline.token` | `STREAMLINE_TOKEN` | empty |

`latency-analyzer` additionally writes `Result/MessageMetrics.csv` and `Result/Throughput.csv`.

### What the numbers mean

A message counts as successful only when the server answers it with `OK`. Two
things that look like success are deliberately excluded:

- **Refusals.** An `ERROR` is still a reply. Counting it made a run where the
  server rejected everything report a hundred percent success rate.
- **Other clients' traffic.** `BROADCAST`, `HISTORY` and `PRESENCE` frames arrive
  because of what other people did. Releasing a sender on one of those let it
  count a success before its own message had been processed, which inflated
  throughput whenever fan-out was enabled.

Each connection joins once and sends everything under that one username, because
the server holds a session to the identity it joined with. The generator
therefore produces `TEXT` only: a mid-stream `LEAVE` would drop the session and
every later message on that connection would be refused.

## Observability

| Signal | Where |
| --- | --- |
| Traffic counters | `streamline.messages.*` on `/actuator/metrics` |
| Room occupancy and caps | `streamline.rooms.*`, and the `limits` block of `/stats` |
| Write queue pressure | `streamline.persistence.*`; depth approaching capacity means writes are running on the caller |
| Structured logs | `SPRING_PROFILES_ACTIVE=json`, ECS format on stdout |

Occupancy is reported next to the configured cap so an alert can fire on the ratio; a bare
count says nothing about whether the server is about to start refusing joins.

## Development

```bash
make help      # list every task
make verify    # every module, clean, with tests
make smoke     # start the server, drive it with the real client, assert every message lands
make run       # server on :8080 with the browser client at /
make bench     # throughput benchmark against a running server
make check-attribution  # confirm GitHub will credit recent commits
```

`make smoke` is the one to run after touching the protocol or the clients. The
unit tests cover each side on its own; the smoke run is what catches the two
disagreeing, which is how a benchmark once reported a hundred percent success
while the server was refusing every message. CI runs the same target.

If the JDK on your PATH is older than 21, point the target at a newer one:

```bash
make smoke JAVA=/path/to/jdk21/bin/java
```

Always build with `clean`; see `CONTRIBUTING.md` for why.

```bash
mvn -f Server/pom.xml clean verify
```

CI runs every module's tests from a clean build and builds the Docker image on each push and
pull request.
