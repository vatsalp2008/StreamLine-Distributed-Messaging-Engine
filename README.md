# StreamLine: Distributed Messaging Engine

StreamLine is a high-performance, distributed messaging system built with Java 21, Spring Boot, and WebSocket. It features asynchronous persistence, real-time message broadcasting, and robust validation.

## Architecture

StreamLine uses a layered architecture to separate concerns and ensure scalability:

- **WebSocket Layer**: Handles real-time connections using `ChatServerWSHandler`.
- **Service Layer**: Manages business logic and asynchronous operations (`ChatService`).
- **Persistence Layer**: Uses JPA and H2 (File-based) for durable storage without heavy infrastructure overhead (`MessageRepository`).

**Key Architectural Decisions:**
1. **Asynchronous Writes (`@Async`)**: Message persistence is decoupled from the WebSocket response loop. This ensures that database I/O never blocks the real-time chat experience.
2. **Event Sourcing (Lite)**: When a user joins, the system replays the recent history of the room, providing context immediately.
3. **Thread Safety**: Uses `ConcurrentHashMap` and `CopyOnWriteArrayList` to manage high-concurrency room sessions safely.

## Features

- **Real-time Communication**: Low-latency WebSocket messaging.
- **Persistence**: Messages are saved to an embedded H2 database.
- **History Replay**: automatic fetching of last 50 messages on join.
- **Validation**: Strict server-side validation for all inputs.
- **Metrics**: Built-in clients for measuring p99 latency and throughput.

## Project Structure

```
Server/
├── src/main/java/server/
│   ├── configure/      # WebSocket Handlers & Config
│   ├── model/          # Entities (ChatMessage)
│   ├── repository/     # Data Access (MessageRepository)
│   └── service/        # Business Logic (ChatService)
├── pom.xml
└── README.md
```

## Running the Server

### 1. Build & Run
```bash
cd Server
mvn clean spring-boot:run
```

The server will start on port 8080 and create a local database file in `./data/streamline`.

### 2. Run Clients (Load Test)
See `load-tester` and `latency-analyzer` loops for performance testing.

# StreamLine-Distributed-Messaging-Engine
