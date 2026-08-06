# StreamLine Latency Analyzer

Measures per-message latency against the StreamLine server and writes CSV reports. Where
`load-tester` answers "how many messages per second", this module answers "how long did each
one take, and how bad is the tail".

## Running

Every setting is read at startup from a system property, falling back to an environment
variable, then to a default; no source edits are needed to change target or load.

```bash
# against a local server
mvn compile exec:java -Dexec.mainClass=client2.MainPhase

# against a remote server, with a smaller run
mvn compile exec:java -Dexec.mainClass=client2.MainPhase \
  -Dstreamline.url=ws://your-host:8080 \
  -Dstreamline.threads=40 \
  -Dstreamline.messages=100000
```

As a self-contained jar:

```bash
mvn clean package
java -Dstreamline.url=ws://your-host:8080 \
     -jar target/streamline-latency-analyzer-1.0.0-jar-with-dependencies.jar
```

## Configuration

| System property | Environment variable | Default |
| --- | --- | --- |
| `streamline.url` | `STREAMLINE_URL` | `ws://localhost:8080` |
| `streamline.threads` | `STREAMLINE_THREADS` | 80 |
| `streamline.messages` | `STREAMLINE_MESSAGES` | 500,000 |
| `streamline.rooms` | `STREAMLINE_ROOMS` | 20 |
| `streamline.result.dir` | `STREAMLINE_RESULT_DIR` | `Result` |

## What it measures

Each sender waits for the server's acknowledgement before sending its next message, and records
the round trip. A message counts as successful only if the acknowledgement arrived, so failures
never flatter the results.

Percentiles use the nearest-rank method: the p-th percentile is the smallest value at or below
which at least p percent of samples fall.

## Console output

```
 ------------ RESULTS ---------------
Successful: 500000
Failed: 0
Runtime: 290396 ms
Throughput: 1721.78 msg/sec
Connections: 80
Reconnections: 0

-------- Statistical Analysis ------
  Mean: 42.31 ms
  Median: 38.00 ms
  95th percentile: 114 ms
  99th percentile: 162 ms
  Min: 1 ms
  Max: 843 ms
```

## CSV reports

Written to `Result/` by default:

```
Result/
├── MessageMetrics.csv     one row per message
└── Throughput.csv         messages per second, in 10 second buckets
```

`MessageMetrics.csv` columns:

| Column | Meaning |
| --- | --- |
| `timestamp` | When the message was sent, epoch milliseconds |
| `messageType` | `TEXT`, `JOIN`, or `LEAVE` |
| `latency` | Round trip to the acknowledgement, in milliseconds |
| `statusCode` | `OK` or `ERROR` |
| `roomId` | Room the message was sent to |

`Throughput.csv` has `Time_Seconds,Messages_Per_Second` and omits idle buckets, so it can be
plotted directly as a throughput-over-time chart.

## How a run works

1. A generator thread fills a bounded queue with random messages: 90% `TEXT`, 5% `JOIN`,
   5% `LEAVE`.
2. Sender threads connect, `JOIN` their room, then send and wait for each acknowledgement,
   recording one `MessageData` row per message.
3. When all senders finish, latency statistics are printed and both CSV files are written.

## Files

| File | Role |
| --- | --- |
| `MainPhase.java` | Entry point: runs the load and prints the summary |
| `MSGSenderThread.java` | One connection; times each request and response |
| `GenerateMessage.java` | Produces the random message stream |
| `MessageData.java` | One immutable measurement row |
| `LatencyStats.java` | Mean, median, min, max, and nearest-rank percentiles |
| `CsvReportWriter.java` | Writes both CSV reports |
| `TestConfig.java` | Resolves settings from properties and the environment |

`LatencyStats` and `CsvReportWriter` are covered by unit tests: `mvn test`.

## Reading the numbers

- **Median** is the typical experience; half of all messages were faster.
- **95th / 99th percentile** are the tail. A median of 38 ms with a p99 of 162 ms means most
  messages are fine while the slowest 1% wait roughly four times as long, which usually points
  at queueing or garbage collection rather than steady-state cost.
- **Max** is a single worst case, often just connection setup or a GC pause; treat the
  percentiles as the real signal.
