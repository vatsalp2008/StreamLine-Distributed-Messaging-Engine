# StreamLine Load Tester

Throughput benchmark for the StreamLine server. Opens many concurrent WebSocket connections,
sends generated chat traffic, and reports how many messages the server acknowledged per second.

## Phases

| Phase | Default threads | Default messages | Purpose |
| --- | --- | --- | --- |
| `client.WarmUpPhase` | 32 | 32,000 | Warms JIT, connection pools, and the DB before measuring |
| `client.MainPhase` | 100 | 500,000 | The measured run |

Both are thin wrappers over `BenchmarkRunner`, which owns the actual load logic.

## Running

Nothing needs to be edited or recompiled to point at a different server: every setting is read
at startup from a system property, falling back to an environment variable, then to the default.

```bash
# against a local server
mvn compile exec:java -Dexec.mainClass=client.WarmUpPhase

# against a remote server, with a smaller run
mvn compile exec:java -Dexec.mainClass=client.MainPhase \
  -Dstreamline.url=ws://your-host:8080 \
  -Dstreamline.threads=50 \
  -Dstreamline.messages=100000
```

As a self-contained jar:

```bash
mvn clean package
java -Dstreamline.url=ws://your-host:8080 \
     -jar target/streamline-load-tester-1.0.0-jar-with-dependencies.jar
```

The jar's manifest runs `WarmUpPhase`; use `-cp target/...jar client.MainPhase` for the main phase.

In Docker, via the repo-root compose file:

```bash
docker compose --profile bench up
```

## Configuration

| System property | Environment variable | Default |
| --- | --- | --- |
| `streamline.url` | `STREAMLINE_URL` | `ws://localhost:8080` |
| `streamline.threads` | `STREAMLINE_THREADS` | 32 warm-up / 100 main |
| `streamline.messages` | `STREAMLINE_MESSAGES` | 32,000 warm-up / 500,000 main |
| `streamline.rooms` | `STREAMLINE_ROOMS` | 20 |

Values that are blank, non-numeric, or non-positive fall back to the default rather than
failing the run. Senders are spread evenly across `streamline.rooms` rooms.

## How a run works

1. A generator thread fills a bounded queue (100,000 slots) with random messages: 90% `TEXT`,
   5% `JOIN`, 5% `LEAVE`.
2. After a 2 second head start, the sender threads connect, each sending `JOIN` first.
3. Each sender takes messages off the queue and waits for the server's acknowledgement before
   sending the next, so the reported rate reflects round trips rather than fire-and-forget
   writes. A send is retried up to 5 times with exponential backoff, and a dropped connection
   is re-established up to 3 times.
4. Once every sender has finished, the totals are printed.

## Example output

```
---------  WarmUp Phase ----------
Successful messages sent: 32000
Failed messages: 0
Total runtime: 23698 ms
Throughput: 1350.32 msg/sec
Total Connections: 32
Reconnections: 0
-----------------------------------------
```

Throughput counts only acknowledged messages, so failures never inflate the number.

## Files

| File | Role |
| --- | --- |
| `BenchmarkRunner.java` | Runs a phase: generator, sender pool, results |
| `MSGSenderThread.java` | One connection; sends and waits for each acknowledgement |
| `GenerateMessage.java` | Produces the random message stream |
| `TestConfig.java` | Resolves settings from properties and the environment |
| `model/ChatMessage.java` | Wire format sent to the server |

## Notes

- Benchmark with `BROADCAST_ENABLED=false` on the server if you want the measurement to reflect
  only the sender's acknowledgement rather than fan-out traffic to other room members.
- For per-message latency percentiles and CSV reports, use the `latency-analyzer` module.
