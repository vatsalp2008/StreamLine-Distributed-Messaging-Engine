# StreamLine Load Tester

This client runs high-volume load tests (up to 500K messages) to stress-test the StreamLine server.

## What It Does

**WarmUp Phase (WarmUpPhase.java):**
- Creates 32 threads
- Each thread sends 1,000 messages
- Total: 32,000 messages
- Simple throughput test

**Main Phase (MainPhase.java):**
- Creates 100 threads (configurable)
- Sends remaining messages to reach 500,000 total
- Includes message generator and queue
- More realistic load test

Both phases measure performance and show success/failure rates.

### Method 1: Run Directly from IDE 

**Step 1: Update the server URL in the code**

Open either `WarmUpPhase.java` or `MainPhase.java` and find this line:
```java
String Server_Url = "ws://localhost:8080/chat";
```

Change it to your EC2 server URL:
```java
String serverUrl = "ws://<your ec2-public-ip>:8080/chat";
```

**Step 2: Right-click and run**

- In IntelliJ: Right-click on `WarmUpPhase.java` → Run 'WarmUpPhase.main()'
- OR: Right-click on `MainPhase.java` → Run 'MainPhase.main()'

That's it! The program will run and show results in the console.

### Method 2: Run from Command Line

If you don't want to change the code, you can pass the URL as an argument:

**For WarmUp Phase:**
```bash
cd load-tester
mvn clean compile
mvn exec:java -Dexec.mainClass="client.model.WarmUpPhase" -Dexec.args="ws://YOUR-EC2-IP:8080/chat"
```

**For Main Phase:**
```bash
cd load-tester
mvn clean compile
mvn exec:java -Dexec.mainClass="client.model.MainPhase" -Dexec.args="ws://YOUR-EC2-IP:8080/chat"
```

### Examples:

```bash
# WarmUp with EC2 server
mvn exec:java -Dexec.mainClass="client.model.WarmUpPhase" -Dexec.args="ws://ec2-52-12-34-56.us-west-2.compute.amazonaws.com:8080/chat"

# Main Phase with EC2 server
mvn exec:java -Dexec.mainClass="client.model.MainPhase" -Dexec.args="ws://ec2-52-12-34-56.us-west-2.compute.amazonaws.com:8080/chat"

# With localhost (for testing)
mvn exec:java -Dexec.mainClass="client.model.WarmUpPhase" -Dexec.args="ws://localhost:8080/chat"
```

## Quick Start (Recommended Way)

1. **Start your server on EC2**
2. **Get your EC2 public IP/DNS**
3. **Update the URL in code**
4. **Run it**
5. **Repeat for Main Phase**

## What You'll See

### WarmUp Phase Output:

```
---------- WarmUp Phase ----------
Successful messages: 32000
Failed messages: 0
Total runtime: 23698 ms
Throughput: 1350.32 msg/sec
Connections: 32
Reconnections: 0
------------------------------------
```

### Main Phase Output:

```
---------- Main Phase Client1 ----------
Successful messages sent: 499998
Failed messages: 2
Total runtime: 290396 ms
Throughput: 1721.78 msg/sec
Total Connections: 100
Reconnections: 0
-----------------------------------------
```

## How They Work

### WarmUp Phase:
1. Creates 32 threads
2. Each thread opens a WebSocket connection
3. Each thread sends 1,000 messages one by one
4. Waits for server response after each message
5. Shows results when done

### Main Phase:
1. Creates a message generator thread
2. Generator creates all messages and puts them in a queue
3. Creates 100 sender threads
4. Each sender thread takes messages from queue and sends them
5. Keeps connections open (more efficient)
6. Shows results when done

## Files in This Project

- `WarmUpPhase.java` - Entry point for warmup (32 threads, 32K messages)
- `MainPhase.java` - Entry point for main load (80 threads, 500k messages)
- `MSGSenderThread.java` - Worker thread that sends messages
- `GenerateMessage.java` - Creates random messages
- `ChatMessage.java` - Message data structure
