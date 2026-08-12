# Contributing to StreamLine

## Layout

| Module | What it is |
| --- | --- |
| `Server/` | Spring Boot WebSocket server, REST API, and browser client |
| `bench-common/` | Message model, config, backoff and generator shared by both clients |
| `load-tester/` | Throughput benchmark (warm-up and main phases) |
| `latency-analyzer/` | Latency benchmark; writes CSV reports |

They are independent Maven projects. There is no aggregator POM, so every Maven
command needs `-f <module>/pom.xml`, or use the `Makefile` targets.

`load-tester` and `latency-analyzer` depend on `bench-common` through the local
repository, so it must be installed before either will resolve:

```bash
mvn install -f bench-common/pom.xml   # or: make common
```

Anything shared by both clients belongs in `bench-common`. The two used to carry
near-identical copies of the message model, config reader and retry logic, and
they drifted.

## Prerequisites

- JDK 21. Every module targets release 21; a JDK 17 runtime cannot run the
  built jars.
- Maven 3.9+
- Docker, only for `make docker-*`

## Everyday commands

```bash
make help      # list every target
make verify    # what CI runs: every module, clean, with tests
make run       # server on :8080, browser client at /
make bench     # throughput benchmark against a running server
```

## Always build clean

Use `mvn clean verify`, never a bare `mvn verify`.

An incremental build reuses classes already in `target/`. That has already let a
commit land whose sources did not compile from scratch: the previous class files
satisfied the test run. CI uses `clean` for this reason and local runs should
match.

This bites hardest across modules. `bench-common` is packaged *into* the client
jars, so a client built without `clean` can bundle a stale copy of it and fail at
runtime with `NoSuchMethodError` for a method that plainly exists in the source.
After changing `bench-common`, reinstall it before rebuilding a client:

```bash
mvn -f bench-common/pom.xml clean install
mvn -f load-tester/pom.xml clean verify
```

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
- `ChatApiIntegrationTest` drives the REST API over real HTTP. Use it for things
  that only exist in a full application, such as JSON serialisation of an
  `Instant` or the correlation id filter; use MockMvc for controller logic.
- `ChatServerWSHandler` has package-private constructors for tests, so a test
  does not need a `StreamlineProperties` or a real `MeterRegistry`.
- `BenchmarkRunnerTest` runs a real WebSocket server in-process rather than
  mocking the transport, because what it is checking is that the generator,
  queue, sender threads and acknowledgement handshake line up.
- Derived Spring Data queries are only proven by running them. `MessageSearchTest`
  uses `@DataJpaTest` against a real database for that reason: a mocked
  repository would not catch a method name that does not parse.
- A worker thread submitted to an executor swallows anything that is not caught,
  because nobody reads the `Future`. Both benchmark clients catch `Throwable`, not
  `Exception`, so an `Error` is reported instead of showing up as a silent run
  with zero traffic.

## Configuration

Every setting has a working default and is overridable by environment variable;
see the table in `README.md`. Two worth knowing while developing:

- `BROADCAST_ENABLED=false` turns off fan-out, so a benchmark measures only the
  sender's acknowledgement.
- `RATE_LIMIT_ENABLED=true` turns on per-session limits, off by default so
  benchmarks are not throttled.

## Running the smoke test

`make smoke` starts the real server and drives it with the real client. Two
things about it are worth knowing before you trust a result:

- **It needs Java 21 or newer.** The target uses whatever `java` is on `PATH`,
  which on many machines is older than the jar. It now says so rather than
  failing with a stack trace in a log file, but you may need
  `make smoke JAVA=/path/to/jdk21+/bin/java`.
- **It refuses to run if the port is busy.** Before that check existed, a
  leftover server from an earlier run answered the health probe and the smoke
  test "passed" without exercising the build at all. If you see the port-in-use
  message, something is still listening; `lsof -ti :18099` will name it.

## Access control

Auth is off by default. To exercise it locally:

```bash
AUTH_ENABLED=true AUTH_TOKEN=a-long-random-secret make run
make warmup URL=ws://localhost:8080 STREAMLINE_TOKEN=a-long-random-secret
```

Two things are deliberate and worth preserving:

- `/health` and `/ready` stay open. A load balancer has no token, and locking it
  out makes a healthy instance look permanently down.
- The token is accepted as a query parameter as well as a header. A browser
  cannot set headers on a WebSocket handshake, so without it the bundled client
  could not connect at all.

## Identity rules

A session is bound to the username it sent with `JOIN`, and a username may appear
only once per room. Both are on by default (`streamline.identity.*`) because they
are protocol correctness, not deployment policy: without them one connection can
attribute messages to anyone, and a room's history means nothing.

A load generator that deliberately reuses one connection for many synthetic
authors must set `IDENTITY_STRICT=false`. The bundled clients do not need it —
each connection joins once and stamps its own identity on everything it sends.

## Measuring correctly

The benchmark clients count a message as successful only when the server answers
it with `OK`. Two mistakes are already fixed here and worth not reintroducing:

- **A refusal is still a reply.** Counting any inbound frame as success meant a
  run where the server rejected every single message reported a hundred percent
  success rate.
- **Fan-out is someone else's traffic.** `BROADCAST`, `HISTORY` and `PRESENCE`
  frames arrive because of other clients. Releasing a waiting sender on one of
  them let it count a success before its own message was processed, inflating
  throughput by roughly 40% whenever broadcast was enabled.

`ServerResponse` encodes both rules; use it rather than inspecting frames by hand.
Never classify a frame with `payload.contains("ERROR")` — a message whose text
mentions the word would be misread.

Run `make smoke` before pushing anything that touches the protocol or the
clients. It starts the real server, drives it with the real client, and fails
unless every message is accepted. CI runs the same target.

## Protocol changes

The two sides of this repo are a server and two clients that must agree. When
changing frames:

- Keep new fields optional. `clientId` is echoed only when the sender supplies
  one, so a client that predates it is unaffected — and the client only rejects
  a reply whose id *differs*, rather than requiring one, so it still works
  against a server that does not echo ids at all.
- Run `make smoke` afterwards. Unit tests cover each side against a stub; only
  the smoke run catches the two halves disagreeing.
- New unsolicited frames (`PRESENCE`, `TYPING`, `DELIVERED`) must not be treated
  as acknowledgements. `ServerResponse.isDirectReply` is the single place that
  decides this; a sender released by someone else's traffic silently inflates
  throughput.
- Persist only what the room accepted. Writing before `chatMessageTypeProcess`
  stored messages that were then refused — a `TEXT` sent before `JOIN` ended up
  in history and search despite the sender getting an `ERROR`.
- A receipt must follow the acknowledgement it confirms, so the write is started
  after the `OK` is sent. The reverse order would tell a client its message was
  stored before telling it the message was accepted.
- Frames the server invented (`REDACTED`, and anything added later) must be
  classified in `ServerResponse` before a benchmark sees them. The default for
  an unrecognised status is "not my acknowledgement", so a new frame type cannot
  silently release a waiting sender.
- A `WebSocketSession` may only be written by one thread at a time, and a bare
  `synchronized` block is not enough. Once receipts started arriving from the
  persistence pool while acknowledgements were still going out on the WebSocket
  thread, sends failed with
  `state [TEXT_PARTIAL_WRITING] which is an invalid state`, and a message was
  lost per run. Every send now goes through a
  `ConcurrentWebSocketSessionDecorator` held in `senders`; use that, not the raw
  session, for anything sent outside the handler thread.
- An edit keeps the original timestamp. Rewriting it would move the message
  within the room's history, so a correction would silently reorder a
  conversation.
- Per-session state keyed by session id (`senders`, `rateLimiters`) is released
  in `afterConnectionClosed`, but that callback is not guaranteed for every
  abnormal termination. `sweepClosedSenders` exists because a missed one leaks
  a decorator and its queue for the life of the process.
- Nothing about tracking a write may fail the message that caused it. The
  persistence future is guarded against being null and against completing
  exceptionally, because an `OK` has already been promised by then.

## The browser client

`Server/src/main/resources/static/app.js` is a module of this project like any
other, and is tested by `make test-js` (`node --test`, included in `make verify`).

- The harness in `Server/src/test/js/harness.js` hand-stubs the handful of
  browser APIs the client uses rather than depending on jsdom, so the tests run
  anywhere node exists with no install step. It binds `window`, `document`,
  `WebSocket` and `fetch` as parameters instead of mutating real globals.
- Anything worth asserting has to be reachable. `handleFrame` was pulled out of
  an inline `socket.onmessage` closure for exactly that reason; keep new logic
  in named functions and add it to the `StreamLineClient` export.
- Reset anything static in the harness between loads. `FakeSocket.last` leaked
  between tests and made a "never connected" assertion pass on a socket left
  over from an earlier one.
- Render user-supplied text with `textContent`, never `innerHTML`. The server
  validates usernames, but the client must not be the thing relying on that.

## Trusting a forwarding header

`X-Forwarded-For` is caller-supplied. `ClientAddressResolver` reads it only when
the request arrived from an address in `streamline.proxy.trusted`, and walks the
chain from the right past your own proxies. Honouring it unconditionally would
let any caller claim a new address per request and slip the rate limit entirely,
which is worse than the coarse bucketing it was meant to fix.

## Compose profiles

The stack has three: `h2`, `postgres`, and `bench`. Two rules learned the hard
way:

- A service must be in every profile that depends on it. `load-tester` depends on
  `server`, so `server` is in both `h2` and `bench`; naming a dependency a
  profile does not activate makes the whole project invalid, not just that
  service unavailable.
- Two services must not build the same image tag. `server` and `server-postgres`
  share a Dockerfile but tag separately, because building both in one `up`
  otherwise fails.

CI asserts which services each profile starts, not merely that the file parses.

## Schema changes

Flyway owns the schema and Hibernate runs with `ddl-auto=validate`, so an entity
change without a matching migration fails at startup instead of silently
altering a table. To change the schema, add a new
`Server/src/main/resources/db/migration/V<n>__<description>.sql`; never edit a
migration that has already been applied.

To see the DDL Hibernate expects for an entity:

```bash
java -jar Server/target/streamline-server-*.jar \
  --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create \
  --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=schema.sql
```

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
