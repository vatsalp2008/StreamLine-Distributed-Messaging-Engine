# StreamLine Latency Analyzer

This client sends 500,000 messages and collects detailed performance metrics for analysis, including p99 latency.

## What It Does

**Main Phase (MainPhase.java):**
- Generates 500,000 messages
- Creates 80 threads 
- Uses message queue for efficiency
- Records latency for every single message
- Calculates detailed statistics (mean, median, percentiles)
- Generates CSV files with all metrics
- Creates throughput chart

**Key Features:**
- Tracks timing for each message
- Calculates 95th and 99th percentile latency
- Shows throughput by room
- Message type distribution analysis
- Exports data to CSV for further analysis

## How to Run

### Method 1: Run Directly from IDE 

**Step 1: Update the server URL in the code**

Open `MainPhase.java` and find this line:
```java
String Server_Url = "ws://localhost:8080/chat";
```

Change it to your EC2 server URL:
```java
String Server_Url = "ws://<your-ec2-public-ip>:8080/chat";
```

**Step 2: Right-click and run**

- In IntelliJ: Right-click on `MainPhase.java` → Run 'MainPhase.main()'

That's it! The program will:
- Run the test (takes ~5-7 minutes)
- Show progress in console
- Generate CSV files in `Result/` folder
- Create throughput chart
- Display statistics at the end

### Method 2: Run from Command Line

```bash
cd latency-analyzer
mvn clean compile
mvn exec:java -Dexec.mainClass="client2.model.MainPhase" -Dexec.args="ws://YOUR-EC2-IP:8080/chat"
```

### Example:

```bash
# With EC2 server
mvn exec:java -Dexec.mainClass="client2.model.MainPhase" -Dexec.args="ws://ec2-52-12-34-56.us-west-2.compute.amazonaws.com:8080/chat"

# With localhost (for testing)
mvn exec:java -Dexec.mainClass="client2.model.MainPhase" -Dexec.args="ws://localhost:8080/chat"
```

## Quick Start (Recommended Way)

1. **Start your server on EC2**
2. **Get your EC2 public IP/DNS**
3. **Update the URL in code**
4. **Run it**
5. **Check the results**

### Console Output:

```
---------- RESULTS ----------
Successful: 499996
Failed: 4
Runtime: 407546 ms
Throughput: 1226.85 msg/sec
Connections: 80
Reconnections: 0

-------- Statistical Analysis ------
Mean: 57.32 ms
Median: 50 ms
95th percentile: 114 ms
99th percentile: 162 ms
Min: 0 ms
Max: 271 ms
```

### Generated Files

After running, check the `Result/` folder:

```
Result/
├── MessageMetrics.csv              (All message timing data)
├── Throughput.csv                  (Throughput per time window)
└── Throughput over time Chart.png  (Visual chart)
```

**MessageMetrics.csv** contains:
```csv
timestamp,messageType,latency,statusCode,roomId
20251015T1425:12.456Z,TEXT,48,OK,1
20251015T1425:12.502Z,JOIN,45,OK,3
...
```

**Throughput.csv** contains:
```csv
timeWindow,messagesPerSecond
0-10,1850.5
10-20,1923.7
...
```

## How It Works

1. **Message Generation:**
   - Single GenerateMessage thread creates all 500,000 messages
   - Places them in a BlockingQueue
   - Uses random data (userId, roomId, messageType)

2. **Message Sending:**
   - Creates 80-100 MSGSenderThread threads
   - Each thread takes messages from queue
   - Sends via persistent WebSocket connection
   - Records timing for each message

3. **Metrics Collection:**
   - Records timestamp before sending
   - Records timestamp after receiving response
   - Calculates latency (end - start)
   - Stores in MessageData object

4. **Analysis:**
   - After all messages sent, analyzes all timing data
   - Calculates mean, median, percentiles
   - Generates CSV files
   - Creates throughput chart

## Configuration

You can adjust the number of sender threads in `MainPhase.java`:

```java
private static final int SENDER_THREADS = 80; // Change this number
```

## Files in This Project

- `MainPhase.java` - Main entry point, orchestrates everything
- `GenerateMessage.java` - Producer thread, creates all messages
- `MSGSenderThread.java` - Consumer threads, send messages
- `MessageData.java` (or `Metric.java`) - Stores timing data per message
- `ChatMessage.java` - Message data structure

## Understanding the Metrics

**Mean (Average):**
- Sum of all latencies / total messages
- Represents typical performance

**Median (50th percentile):**
- Middle value when all sorted
- Half messages faster, half slower

**95th Percentile:**
- 95% of messages completed within this time
- Important for SLA guarantees

**99th Percentile:**
- 99% of messages completed within this time
- Shows tail latency (worst 1%)

**Throughput:**
- Messages per second
- Overall system performance

## Generating the Throughput Chart

### Method 1: Manual with Google Sheets (Easiest)

**Step 1: Open the CSV file**
- Go to `Result/` folder
- Find `Throughput.csv`
- Open it (you'll see time windows and messages per second)

**Step 2: Upload to Google Sheets**
- Go to https://sheets.google.com
- Click "Blank" to create new sheet
- File → Import → Upload
- Select your `Throughput.csv` file
- Click "Import data"

**Step 3: Create the chart**
- Select all the data (click on cell A1, then Ctrl+Shift+End to select all)
- Click "Insert" → "Chart"
- Chart type: Choose "Line chart"
- Google Sheets will automatically create the chart

---

**Bottom line:** Update the URL in MainPhase.java and right-click → Run. Wait 5-7 minutes. Check Result folder for CSV files and chart.
