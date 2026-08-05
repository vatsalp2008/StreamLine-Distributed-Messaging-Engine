# StreamLine Server

The WebSocket messaging server. For the protocol reference and configuration table, see the
[root README](../README.md); this file covers building, running, and deploying the module.

## Overview

Accepts WebSocket connections on `/chat/{roomId}`, validates each frame, acknowledges it to the
sender, fans it out to the rest of the room, and persists it asynchronously to an embedded H2
database. A joining client is replayed the room's recent history.

## Layout

```
Server/
├── src/
│   ├── main/java/server/
│   │   ├── ChatServer.java              # Spring Boot entry point
│   │   ├── configure/
│   │   │   ├── ChatServerWSHandler.java # Connection lifecycle and message routing
│   │   │   ├── ConfigureWebSocket.java  # Endpoint registration and per-connection limits
│   │   │   ├── AsyncConfig.java         # Bounded pool backing async persistence
│   │   │   ├── MetricsConfig.java       # Room gauges published to Actuator
│   │   │   └── ServerStatus.java        # /health and /stats
│   │   ├── model/ChatMessage.java       # Validated JPA entity
│   │   ├── repository/MessageRepository.java
│   │   └── service/ChatService.java     # Async, transactional persistence
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

Java 21 or newer is required.

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
| `GET /health` | Liveness probe |
| `GET /stats` | Active rooms, joined sessions, per-room occupancy |
| `GET /actuator/health` | Actuator health |
| `GET /actuator/metrics` | JVM, HTTP, and `streamline.rooms.active` / `streamline.sessions.joined` |

## Validation rules

Every field is rejected server-side if it does not match:

| Field | Rule |
|-------|------|
| userId | Between 1 and 100,000 |
| username | 3-20 alphanumeric characters |
| message | 1-500 characters |
| timestamp | Required, ISO-8601 |
| messageType | `TEXT`, `JOIN`, or `LEAVE` |

A validation failure returns `{"status":"ERROR", ...}` and leaves the connection open.

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
