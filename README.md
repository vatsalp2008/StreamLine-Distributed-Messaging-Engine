# StreamLine: Distributed Messaging Engine

StreamLine is a high-throughput WebSocket messaging server built with Java 21 and Spring Boot,
paired with two benchmark clients used to measure its throughput and tail latency.

## Architecture

```
Server/
├── src/main/java/server/
│   ├── configure/      # WebSocket handler, async pool, HTTP status endpoints
│   ├── model/          # ChatMessage entity
│   ├── repository/     # MessageRepository (Spring Data JPA)
│   └── service/        # ChatService (async, transactional persistence)
├── src/test/java/      # Unit tests
├── Dockerfile
└── pom.xml

load-tester/            # Throughput benchmark (warm-up + main phase)
latency-analyzer/       # Per-message latency capture, writes CSV reports
```

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
| `ERROR` | Validation failure or protocol violation |

## HTTP endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Liveness probe, returns `{"status":"RUNNING"}` |
| `GET /stats` | Live room count, joined sessions, and per-room occupancy |

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

## Tests

```bash
mvn -f Server/pom.xml verify
```

CI runs the server test suite, compiles both benchmark clients, and builds the Docker image on
every push and pull request.
