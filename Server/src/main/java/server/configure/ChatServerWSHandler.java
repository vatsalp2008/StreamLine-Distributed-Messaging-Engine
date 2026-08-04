package server.configure;

import server.model.ChatMessage;
import server.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * class ChatServerWebSocketHandler a handler for chat rooms
 */
@Component
public class ChatServerWSHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatServerWSHandler.class);

    private final ObjectMapper objectMapperMSG = new ObjectMapper();
    private final Validator validator;
    private final ChatService chatService;

    // ConcurrentHashMap for thread safe room and list sessions in room
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>> chatRooms = new ConcurrentHashMap<>();

    // set for session storing which are joined
    private final Set<WebSocketSession> joinedSessions = ConcurrentHashMap.newKeySet();

    public ChatServerWSHandler(Validator validator, ChatService chatService) {
        this.validator = validator;
        this.chatService = chatService;
    }

    /**
     * @param session -WebSocketSession, Representing the Session
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("New connection {} to room {}", session.getId(), getRoomId(session));
    }

    /**
     * @param session -WebSocketSession, Representing the Session
     * @param message -WebSocketMessage, Representing the raw msg
     * @throws IOException
     */
    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws IOException {
        try {
            // JSON Paylod parshing into ChatMessage object
            ChatMessage chatMessage = objectMapperMSG.readValue(
                    message.getPayload().toString(), ChatMessage.class);

            // Validate message using annotations
            Set<ConstraintViolation<ChatMessage>> violations = validator.validate(chatMessage);
            if (!violations.isEmpty()) {
                sendResponse(session, "ERROR",
                        "Validation failed: " + violations.iterator().next().getMessage());
                return;
            }

            String roomId = getRoomId(session);

            // Async persist all valid messages
            chatService.saveMessage(chatMessage, roomId);

            // method for getting formatted message to send in response
            String formattedMessage = chatMessageTypeProcess(session, roomId, chatMessage);

            // echoback to sender from server
            if (formattedMessage != null) {
                sendResponse(session, "OK", formattedMessage);
            }

        } catch (Exception e) {
            sendResponse(session, "ERROR", "Error parsing message: " + e.getMessage());
        }
    }

    /**
     * Processing diff methods types
     * 
     * @param session     -WebSocketSession, Representing the Session
     * @param roomId      -String, Representing teh Room ID
     * @param chatMessage -ChatMessage, Representing the chat msg of Model
     *                    chatMessage
     * @return -String, Representing the chat message
     *         if already joined -> return Null
     *         if not joined -> return Null
     * @throws IOException
     */
    private String chatMessageTypeProcess(WebSocketSession session, String roomId, ChatMessage chatMessage)
            throws IOException {
        switch (chatMessage.getMessageType()) {
            case "JOIN":
                if (joinedSessions.contains(session)) {
                    sendResponse(session, "ERROR", "Already joined");
                    return null;
                }
                chatRooms.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(session);
                joinedSessions.add(session);

                // Send history
                List<ChatMessage> history = chatService.getRecentMessages(roomId);
                // Send in reverse order (chronological) if needed, but they are fetched desc.
                // Reversing to show oldest first is usually better for chat.
                for (int i = history.size() - 1; i >= 0; i--) {
                    ChatMessage pastMsg = history.get(i);
                    sendResponse(session, "HISTORY", pastMsg.getUsername() + ": " + pastMsg.getMessage());
                }

                return chatMessage.getUsername() + " joined the room";

            case "LEAVE":
                if (!joinedSessions.contains(session)) {
                    sendResponse(session, "ERROR", "You must JOIN before LEAVE");
                    return null;
                }
                CopyOnWriteArrayList<WebSocketSession> roomSessions = chatRooms.get(roomId);
                if (roomSessions != null) {
                    roomSessions.remove(session);
                }
                joinedSessions.remove(session);
                return chatMessage.getUsername() + " left the room";

            case "TEXT":
                if (!joinedSessions.contains(session)) {
                    sendResponse(session, "ERROR", "You must JOIN before sending TEXT");
                    return null;
                }
                return chatMessage.getUsername() + ": " + chatMessage.getMessage();

            default:
                sendResponse(session, "ERROR", "Unknown message type");
                return null;
        }
    }

    /**
     * sending JSON response back to sender
     * 
     * @param session -WebSocketSession, Representing the Session
     * @param status  -String, Representing the Status
     * @param message -String, Representing the msg
     */
    private void sendResponse(WebSocketSession session, String status, String message) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", status);
            response.put("serverTimestamp", Instant.now().toString());
            response.put("message", message);

            String jsonResponse = objectMapperMSG.writeValueAsString(response);

            if (session.isOpen()) {
                // Synchronized send to avoid concurrent send issues
                // only one thread will send msg
                synchronized (session) {
                    session.sendMessage(new TextMessage(jsonResponse));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to send response to session {}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * @param session   -WebSocketSession, Representing the Session
     * @param exception -exception, Representing exception to print as an error
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Transport error on session {}: {}", session.getId(), exception.getMessage());
    }

    /**
     * @param session     -WebSocketSession, Representing the Session
     * @param closeStatus -CloseStatus, Representing status of connectionClosed
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        String roomId = getRoomId(session);

        CopyOnWriteArrayList<WebSocketSession> sessions = chatRooms.get(roomId);
        // removing sessions from room and tracking
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                chatRooms.remove(roomId);
            }
        }

        joinedSessions.remove(session);
        log.debug("Connection {} closed from room {} ({})", session.getId(), roomId, closeStatus);
    }

    /**
     * @return false
     */
    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * @param session -WebSocketSession, Representing the Session
     */
    private String getRoomId(WebSocketSession session) {
        return Objects.requireNonNull(session.getUri()).getPath().split("/chat/")[1];
    }
}