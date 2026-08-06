# Contributing to StreamLine

## Layout

| Module | What it is |
| --- | --- |
| `Server/` | Spring Boot WebSocket server, REST API, and browser client |
| `load-tester/` | Throughput benchmark (warm-up and main phases) |
| `latency-analyzer/` | Latency benchmark; writes CSV reports |

The three are independent Maven projects. There is no aggregator POM, so every
Maven command needs `-f <module>/pom.xml`, or use the `Makefile` targets.

## Prerequisites

- JDK 21. All three modules target release 21; a JDK 17 runtime cannot run the
  built jars.
- Maven 3.9+
- Docker, only for `make docker-*`

## Everyday commands

```bash
make help      # list every target
make verify    # what CI runs: all modules, clean, with tests
make run       # server on :8080, browser client at /
make bench     # throughput benchmark against a running server
```

## Always build clean

Use `mvn clean verify`, never a bare `mvn verify`.

An incremental build reuses classes already in `target/`. That has already let a
commit land whose sources did not compile from scratch: the previous class files
satisfied the test run. CI uses `clean` for this reason and local runs should
match.

## Tests

- Unit tests sit beside the code they cover, under `src/test/java`.
- `ChatWebSocketIntegrationTest` starts the real server on a random port and
  talks to it over a real WebSocket. It shares one in-memory database across the
  class, so a test must not assume the database is empty. Use a room name unique
  to the test rather than counting rows globally.
- Prefer injecting a clock over sleeping. `RateLimiter` takes a `LongSupplier`
  and `Backoff` separates the delay calculation from the sleep, so both are
  tested without real waiting.
- Mockito runs as a java agent, configured in `Server/pom.xml`. Without that it
  self-attaches, which the JDK warns about and will eventually refuse.

## Configuration

Every setting has a working default and is overridable by environment variable;
see the table in `README.md`. Two worth knowing while developing:

- `BROADCAST_ENABLED=false` turns off fan-out, so a benchmark measures only the
  sender's acknowledgement.
- `RATE_LIMIT_ENABLED=true` turns on per-session limits, off by default so
  benchmarks are not throttled.

## Style

Match the file you are editing. The server code uses constructor injection,
SLF4J with parameterised messages (never string concatenation), and Javadoc on
public methods.

Comments should explain why something is done, not restate the code. Most
comments in this codebase mark a decision that is not obvious from the syntax,
such as why control frames are not persisted or why history ordering has a
second sort key.

## Commits

Keep the subject short and imperative: "Add readiness probe separate from
liveness". One self-contained change per commit, and the build must pass at
every commit.
