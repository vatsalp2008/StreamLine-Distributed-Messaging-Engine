# WebSocket Chat Server

CS6650 Assignment 1 - Server Implementation

## Overview

This is a WebSocket server that handles real-time chat messages. It validates incoming messages and echoes them back to clients with server timestamps and status codes.

## Features

- WebSocket endpoint: `/chat/{roomId}`
- Message validation (userId, username, message, timestamp, messageType)
- Thread-safe connection handling
- Support for multiple concurrent connections
- Error handling and validation responses
- Session management per room

## Project Structure

```
Server/
├── src/
│   └── main/
│       └── java/
│           └── server/
│               ├── configure/
│               │   ├── ChatServerWSHandler.java    
│               │   ├── ConfigureWebSocket.java    
│               │   └── ServerStatus.java          
│               └── model/
│                   ├── ChatMessage.java            
│                   └── ChatServer.java            
├── pom.xml
└── README.md
```

## Building the Server

### Option 1: Build JAR file

```bash
# Navigate to Server directory
cd Server

# Clean and build
mvn clean package

# JAR file will be created at: target/Server-0.0.1-SNAPSHOT.jar
```

## Deployment on AWS EC2

### Step 1: Connect to EC2

```bash
# Set permissions for your key file
chmod 400 /path/to/your-key.pem

# SSH into EC2
ssh -i /path/to/your-key.pem ec2-user@your-ec2-public-ip
```

### Step 2: Upload Server JAR

From your **local machine** (not EC2):

```bash
# Upload JAR file to EC2
scp -i /path/to/your-key.pem target/Server-0.0.1-SNAPSHOT.jar ec2-user@your-ec2-public-dns:~/

# Example:
scp -i ~/.ssh/cs6650-key.pem target/Server-0.0.1-SNAPSHOT.jar ec2-user@ec2-52-12-34-56.us-west-2.compute.amazonaws.com:~/
```

### Step 3: Run the Server

## Server Configuration

The server runs on **port 8080** by default.

WebSocket endpoint: `ws://your-server-address:8080/chat/{roomId}`

## Message Format

### Client → Server (Request)

```json
{
  "userId": "12345",
  "username": "user1223",
  "message": "Hey JSK!",
  "timestamp": "2025-10-15T14:30:00Z",
  "messageType": "JOIN"
}
```
```

## Validation Rules

The server validates all incoming messages:

| Field | Rule |
|-------|------|
| userId | Must be between 1 and 100,000 |
| username | Must be 3-20 alphanumeric characters |
| message | Must be 1-500 characters |
| timestamp | Must be valid ISO-8601 format |
| messageType | Must be TEXT, JOIN, or LEAVE |

If validation fails, server responds with error status.

## Testing the Server

### Test with wscat (WebSocket client)

```bash
# Install wscat
npm install -g wscat

# Connect to server
wscat -c ws://your-ec2-dns:8080/chat/1

# Join the room 
{"userId":"1","username":"testuser","message":"Hello","timestamp":"2025-10-15T14:30:00Z","messageType":"JOIN"}

# Send a test message
{"userId":"1","username":"testuser","message":"Hello","timestamp":"2025-10-15T14:30:00Z","messageType":"TEXT"}
```

- **ChatServer.java** - Main entry point, starts the server
- **ChatServerWSHandler.java** - Handles WebSocket lifecycle (onOpen, onMessage, onClose, onError)
- **ConfigureWebSocket.java** - Configures WebSocket endpoints
- **Validator** - Validates message format and fields
- **ChatMessage.java** - Data model for messages
- **ServerStatus.java** - Response object with status and timestamp




