# StreamLine Server

The WebSocket messaging server. For the protocol reference and configuration table, see the
[root README](../README.md); this file covers building, running, and deploying the module.

## Overview

Accepts WebSocket connections on `/chat/{roomId}`, validates each frame, acknowledges it to the
sender, fans it out to the rest of the room, and persists it asynchronously to an embedded H2
database. A joining client is replayed the room's recent history and told who else is present.

A session is bound to the username it joined with, so a connection cannot attribute messages to
anyone else, and a username may appear only once per room. Both rules can be relaxed for load
generators that reuse one connection for many synthetic authors; see the root README.

Only messages the room accepted are stored: persistence runs after the state-machine check, so a
`TEXT` sent before `JOIN` is refused and never reaches the database. With `RECEIPTS_ENABLED=true`
a `DELIVERED` frame follows the acknowledgement once the row exists, since an `OK` only means the
message was accepted.

Frames referring to a stored message carry its id in `messageId`, and replayed history carries
`editedAt` when the message was rewritten. Editing emits an `EDITED` frame carrying `id:new text`; the stored timestamp is left alone so an
edit cannot reorder history. Deleting a message emits a `REDACTED` frame to the room, so clients
already displaying it can show that it is gone. Deletion is scoped by room: an id from another room is a 404, not a
silent success.

The browser client lives in `src/main/resources/static` and is tested by `make test-js`, which
runs `node --test` against a hand-written DOM stub — no npm install, so it runs in CI unchanged.
Your own messages gain edit and delete controls once a receipt reports their stored id; a receipt
is the only source of that id, so the controls cannot appear on anyone else's messages.

`GET /stats` reports write-queue saturation and room-token rotation state alongside occupancy, so
a rotation that failed to read is distinguishable from a file listing no rooms.

## Layout

```
Server/
├── src/
│   ├── main/java/server/
│   │   ├── ChatServer.java              # Spring Boot entry point
│   │   ├── configure/
│   │   │   ├── ChatServerWSHandler.java # Connection lifecycle and message routing
│   │   │   ├── TokenAuthenticator.java  # Shared-secret checks, constant time
│   │   │   ├── TokenAuthFilter.java     # Token enforcement on HTTP
│   │   │   ├── TokenHandshakeInterceptor.java # Token enforcement on /chat
│   │   │   ├── RateLimiter.java         # Per-session token bucket
│   │   │   ├── ConfigureWebSocket.java  # Endpoint registration and per-connection limits
│   │   │   ├── AsyncConfig.java         # Bounded pool backing async persistence
│   │   │   ├── MetricsConfig.java       # Traffic counters published to Actuator
│   │   │   ├── RoomGauges.java          # Live room occupancy and caps
│   │   │   ├── PersistenceGauges.java   # Write queue depth and throughput
│   │   │   ├── ApiRateLimitFilter.java  # Per-address limit on the HTTP API
│   │   │   ├── ClientAddressResolver.java # X-Forwarded-For, only from trusted proxies
│   │   │   ├── RoomTokenStore.java      # Room secrets, reloadable while running
│   │   │   └── ServerStatus.java        # /health, /ready and /stats
│   │   ├── model/ChatMessage.java       # Validated JPA entity
│   │   ├── api/                         # Read-only HTTP API and error handling
│   │   ├── repository/MessageRepository.java
│   │   └── service/
│   │       ├── ChatService.java         # Async, transactional persistence
│   │       └── RetentionService.java    # Scheduled pruning of old messages
│   ├── main/resources/db/migration/     # Flyway schema migrations
│   └── test/java/server/                # Unit and end-to-end tests
├── Dockerfile
├── pom.xml
└── README.md
```

## Build and run

```bash
mvn spring-boot:run                 # run locally on port 8080
mvn clean package                   # build target/streamline-server-0.0.1-SNAPSHOT.jar
mvn verify                          # compile and run the full test suite
java -jar target/streamline-server-0.0.1-SNAPSHOT.jar
```

Java 21 or newer is required. `make smoke` from the repo root starts this server and drives it
with the real benchmark client, which is the check that catches the server and clients
disagreeing about the protocol.

### Docker

```bash
docker build -t streamline-server .
docker run -p 8080:8080 -v streamline-data:/app/data streamline-server
```

The image runs as an unprivileged user and keeps the H2 file on the `/app/data` volume, so
messages survive container restarts.

## Endpoints

| Endpoint | Purpose |
| --- | --- |
| `ws://<host>:8080/chat/{roomId}` | Chat connection |
| `GET /health` | Liveness probe; does no I/O |
| `GET /ready` | Readiness probe; checks the database, 503 when not ready |
| `GET /stats` | Active rooms, joined sessions, occupancy, and the configured caps |
| `GET /api/rooms` | Rooms with at least one member |
| `GET /api/rooms/{id}/messages` | Paginated history |
| `GET /api/rooms/{id}/search` | Search history by text and optionally author |
| `PATCH /api/rooms/{roomId}/messages/{id}` | Replace a message's text; 200, or 404 if not in that room |
| `DELETE /api/rooms/{roomId}/messages/{id}` | Remove one message; 204, or 404 if not in that room |
| `GET /actuator/metrics` | JVM, HTTP, `streamline.messages.*`, `streamline.rooms.*`, `streamline.persistence.*` |

## Validation rules

Every field is rejected server-side if it does not match:

| Field | Rule |
|-------|------|
| userId | Between 1 and 100,000 |
| username | 3-20 alphanumeric characters |
| message | 1-500 characters |
| timestamp | Required, ISO-8601 |
| messageType | `TEXT`, `JOIN`, `LEAVE`, or `TYPING` |
| clientId | Optional, up to 64 characters |

A validation failure returns `{"status":"ERROR", ...}` and leaves the connection open. When the
frame carried a `clientId`, the reply echoes it so the sender knows which message was refused.

## Schema

Flyway owns the schema (`src/main/resources/db/migration`) and Hibernate runs with
`ddl-auto=validate`, so an entity change without a matching migration fails at startup instead
of silently altering a table. Add a new `V<n>__<description>.sql` rather than editing an applied
migration.

## Manual testing

```bash
npm install -g wscat
wscat -c ws://localhost:8080/chat/1

# JOIN first; TEXT before JOIN is refused
{"userId":1,"username":"testuser","message":"Hello","timestamp":"2026-08-05T14:30:00Z","messageType":"JOIN"}
{"userId":1,"username":"testuser","message":"Hello","timestamp":"2026-08-05T14:30:00Z","messageType":"TEXT"}
```

Open a second `wscat` against the same room to watch messages arrive as `BROADCAST`.

## Deploying to EC2

```bash
chmod 400 /path/to/your-key.pem
scp -i /path/to/your-key.pem \
  target/streamline-server-0.0.1-SNAPSHOT.jar ec2-user@<public-dns>:~/
ssh -i /path/to/your-key.pem ec2-user@<public-dns>

# on the instance
nohup java -jar streamline-server-0.0.1-SNAPSHOT.jar > streamline.log 2>&1 &
```

Open port 8080 in the instance security group, and set `DB_PASSWORD` in the environment rather
than relying on the empty default.
