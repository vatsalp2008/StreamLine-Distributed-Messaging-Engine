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
import java.net.URI;
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

    /** URI segment that precedes the room id, e.g. ws://host/chat/12 */
    private static final String ROOM_PATH_PREFIX = "/chat/";

    /** Fallback room used when the handshake URI carries no usable room id */
    static final String UNKNOWN_ROOM = "unknown";

    /** Status of a frame announcing who is in the room */
    static final String PRESENCE = "PRESENCE";

    /** Status of a frame announcing that someone is composing a message */
    static final String TYPING_STATUS = "TYPING";

    /** Message types accepted by the protocol */
    private static final String JOIN = "JOIN";
    private static final String LEAVE = "LEAVE";
    private static final String TEXT = "TEXT";
    private static final String TYPING = "TYPING";

    private final ObjectMapper objectMapperMSG;
    private final Validator validator;
    private final ChatService chatService;

    // ConcurrentHashMap for thread safe room and list sessions in room
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>> chatRooms = new ConcurrentHashMap<>();

    // set for session storing which are joined
    private final Set<WebSocketSession> joinedSessions = ConcurrentHashMap.newKeySet();

    /**
     * Username claimed by each joined session, keyed by session id.
     * Kept separately from chatRooms because a closing connection has to be
     * resolved back to its username without parsing another frame.
     */
    private final ConcurrentHashMap<String, String> sessionUsernames = new ConcurrentHashMap<>();

    /**
     * Fan-out is enabled by default. Load and latency benchmarks can turn it off
     * (streamline.broadcast.enabled=false) so measurements only see the direct ack.
     */
    private final boolean broadcastEnabled;

    /** Per-session send limits; empty when rate limiting is disabled. */
    private final StreamlineProperties.RateLimit rateLimitSettings;

    /** How strictly a session is held to the username it joined with. */
    private final StreamlineProperties.Identity identitySettings;

    /** Caps on how much room state this server will hold. */
    private final StreamlineProperties.Limits limits;
    private final ChatMetrics metrics;
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ChatServerWSHandler(Validator validator, ChatService chatService,
            StreamlineProperties properties, ObjectMapper objectMapper, ChatMetrics metrics) {
        this(validator, chatService, properties.getBroadcast().isEnabled(), objectMapper,
                properties.getRateLimit(), metrics, properties.getIdentity(),
                properties.getLimits());
    }

    /** Direct constructor, used by tests that do not need a properties object. */
    ChatServerWSHandler(Validator validator, ChatService chatService, boolean broadcastEnabled) {
        this(validator, chatService, broadcastEnabled, defaultObjectMapper(),
                new StreamlineProperties.RateLimit(), noOpMetrics());
    }

    ChatServerWSHandler(Validator validator, ChatService chatService, boolean broadcastEnabled,
            ObjectMapper objectMapper) {
        this(validator, chatService, broadcastEnabled, objectMapper,
                new StreamlineProperties.RateLimit(), noOpMetrics());
    }

    ChatServerWSHandler(Validator validator, ChatService chatService, boolean broadcastEnabled,
            ObjectMapper objectMapper, StreamlineProperties.RateLimit rateLimitSettings,
            ChatMetrics metrics) {
        this(validator, chatService, broadcastEnabled, objectMapper, rateLimitSettings, metrics,
                new StreamlineProperties.Identity());
    }

    ChatServerWSHandler(Validator validator, ChatService chatService, boolean broadcastEnabled,
            ObjectMapper objectMapper, StreamlineProperties.RateLimit rateLimitSettings,
            ChatMetrics metrics, StreamlineProperties.Identity identitySettings) {
        this(validator, chatService, broadcastEnabled, objectMapper, rateLimitSettings, metrics,
                identitySettings, new StreamlineProperties.Limits());
    }

    ChatServerWSHandler(Validator validator, ChatService chatService, boolean broadcastEnabled,
            ObjectMapper objectMapper, StreamlineProperties.RateLimit rateLimitSettings,
            ChatMetrics metrics, StreamlineProperties.Identity identitySettings,
            StreamlineProperties.Limits limits) {
        this.limits = limits;
        this.identitySettings = identitySettings;
        this.validator = validator;
        this.chatService = chatService;
        this.broadcastEnabled = broadcastEnabled;
        this.objectMapperMSG = objectMapper;
        this.rateLimitSettings = rateLimitSettings;
        this.metrics = metrics;
    }

    /** Metrics sink for handlers built outside Spring, backed by a throwaway registry. */
    private static ChatMetrics noOpMetrics() {
        return new ChatMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /**
     * Mapper used when the handler is built outside Spring. Mirrors the Boot
     * defaults that matter here: java.time support and ISO-8601 rather than
     * numeric timestamps.
     */
    private static ObjectMapper defaultObjectMapper() {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
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
        // Checked before parsing, so a flood costs as little work as possible.
        if (!allowedByRateLimit(session)) {
            metrics.recordRateLimited();
            sendResponse(session, "ERROR", "Rate limit exceeded, slow down");
            return;
        }

        try {
            // JSON Paylod parshing into ChatMessage object
            ChatMessage chatMessage = objectMapperMSG.readValue(
                    message.getPayload().toString(), ChatMessage.class);

            // Validate message using annotations
            Set<ConstraintViolation<ChatMessage>> violations = validator.validate(chatMessage);
            if (!violations.isEmpty()) {
                metrics.recordRejected();
                sendResponse(session, "ERROR",
                        "Validation failed: " + violations.iterator().next().getMessage(),
                        chatMessage.getClientId());
                return;
            }

            String roomId = getRoomId(session);

            String capacityProblem = capacityProblem(roomId, chatMessage);
            if (capacityProblem != null) {
                metrics.recordRejected();
                sendResponse(session, "ERROR", capacityProblem, chatMessage.getClientId());
                return;
            }

            String identityProblem = identityProblem(session, roomId, chatMessage);
            if (identityProblem != null) {
                metrics.recordIdentityRejected();
                sendResponse(session, "ERROR", identityProblem, chatMessage.getClientId());
                return;
            }

            // Only chat content is durable. JOIN and LEAVE are connection control
            // frames: storing them replayed "alice: Joining" back as room history, and
            // a client could even receive its own JOIN as HISTORY when the async write
            // beat the history query. They are also ~10% of benchmark traffic, so
            // skipping them removes that many writes from the hot path.
            if (TEXT.equals(chatMessage.getMessageType())) {
                chatService.saveMessage(chatMessage, roomId);
            }

            // method for getting formatted message to send in response
            String formattedMessage = chatMessageTypeProcess(session, roomId, chatMessage);

            // echoback to sender from server, then fan out to everyone else in the room
            if (formattedMessage != null) {
                metrics.recordAccepted();
                sendResponse(session, "OK", formattedMessage, chatMessage.getClientId());
                broadcast(roomId, session, formattedMessage);

                // JOIN and LEAVE change who is present, so push the new member
                // list rather than making clients poll for it
                String type = chatMessage.getMessageType();
                if (JOIN.equals(type) || LEAVE.equals(type)) {
                    announcePresence(roomId);
                }
            } else {
                // the type handler already answered with an ERROR
                metrics.recordRejected();
            }

        } catch (Exception e) {
            metrics.recordRejected();
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
            case JOIN:
                if (joinedSessions.contains(session)) {
                    sendResponse(session, "ERROR", "Already joined", chatMessage.getClientId());
                    return null;
                }
                chatRooms.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(session);
                joinedSessions.add(session);
                rememberUsername(session, chatMessage.getUsername());

                // Send history
                List<ChatMessage> history = chatService.getRecentMessages(roomId);
                // Send in reverse order (chronological) if needed, but they are fetched desc.
                // Reversing to show oldest first is usually better for chat.
                for (int i = history.size() - 1; i >= 0; i--) {
                    ChatMessage pastMsg = history.get(i);
                    sendResponse(session, "HISTORY", pastMsg.getUsername() + ": " + pastMsg.getMessage());
                }

                return chatMessage.getUsername() + " joined the room";

            case LEAVE:
                if (!joinedSessions.contains(session)) {
                    sendResponse(session, "ERROR", "You must JOIN before LEAVE", chatMessage.getClientId());
                    return null;
                }
                CopyOnWriteArrayList<WebSocketSession> roomSessions = chatRooms.get(roomId);
                if (roomSessions != null) {
                    roomSessions.remove(session);
                }
                joinedSessions.remove(session);
                forgetUsername(session);
                return chatMessage.getUsername() + " left the room";

            case TEXT:
                if (!joinedSessions.contains(session)) {
                    sendResponse(session, "ERROR", "You must JOIN before sending TEXT", chatMessage.getClientId());
                    return null;
                }
                return chatMessage.getUsername() + ": " + chatMessage.getMessage();

            case TYPING:
                if (!joinedSessions.contains(session)) {
                    sendResponse(session, "ERROR", "You must JOIN before sending TYPING", chatMessage.getClientId());
                    return null;
                }

                // Transient by design: never stored, never echoed to the sender,
                // and returns null so the caller does not also fan it out as chat.
                announceTyping(roomId, session, chatMessage.getUsername());
                return null;

            default:
                sendResponse(session, "ERROR", "Unknown message type", chatMessage.getClientId());
                return null;
        }
    }

    /**
     * @return the number of rooms that currently hold at least one joined session
     */
    public int getActiveRoomCount() {
        return chatRooms.size();
    }

    /**
     * @return the number of sessions that have completed a JOIN and not yet left
     */
    public int getJoinedSessionCount() {
        return joinedSessions.size();
    }

    /**
     * Members currently joined to a room, by username.
     *
     * The same username may be connected more than once, so duplicates are
     * collapsed: this answers "who is here", not "how many sockets are open".
     * Use {@link #getRoomOccupancy()} for the session count.
     *
     * @param roomId -String, Representing the room to inspect
     * @return sorted usernames, empty when the room has no members
     */
    public List<String> getRoomMembers(String roomId) {
        CopyOnWriteArrayList<WebSocketSession> sessions = chatRooms.get(roomId);
        if (sessions == null) {
            return List.of();
        }

        Set<String> names = new TreeSet<>();
        for (WebSocketSession session : sessions) {
            // a session id is never null in practice, but this runs on the
            // message path and a lookup must not be able to throw
            String id = session.getId();
            String username = id == null ? null : sessionUsernames.get(id);
            if (username != null) {
                names.add(username);
            }
        }
        return List.copyOf(names);
    }

    private void rememberUsername(WebSocketSession session, String username) {
        if (session.getId() != null && username != null) {
            sessionUsernames.put(session.getId(), username);
        }
    }

    private void forgetUsername(WebSocketSession session) {
        if (session.getId() != null) {
            sessionUsernames.remove(session.getId());
        }
    }

    /**
     * @return a point-in-time snapshot of room id to member count
     */
    public Map<String, Integer> getRoomOccupancy() {
        Map<String, Integer> occupancy = new TreeMap<>();
        chatRooms.forEach((roomId, sessions) -> occupancy.put(roomId, sessions.size()));
        return occupancy;
    }

    /**
     * @param session -WebSocketSession, the sender being metered
     * @return true when the session may send another message right now
     */
    private boolean allowedByRateLimit(WebSocketSession session) {
        if (!rateLimitSettings.isEnabled()) {
            return true;
        }

        RateLimiter limiter = rateLimiters.computeIfAbsent(session.getId(),
                id -> new RateLimiter(rateLimitSettings.getMessagesPerSecond(),
                        rateLimitSettings.getBurstSize()));

        return limiter.tryAcquire();
    }

    /**
     * Checks that admitting this JOIN would not exceed the server's caps.
     *
     * Enforced before any room state is allocated, so a refused join leaves
     * nothing behind. Room ids come straight from the connection URL, so
     * without this a client can allocate rooms without bound.
     *
     * @return the reason to refuse, or null when there is room
     */
    private String capacityProblem(String roomId, ChatMessage message) {
        if (!JOIN.equals(message.getMessageType())) {
            return null;
        }

        int maxRooms = limits.getMaxRooms();
        // only a room that does not exist yet would push the count up
        if (maxRooms > 0 && !chatRooms.containsKey(roomId) && chatRooms.size() >= maxRooms) {
            return "Server is at its room limit of " + maxRooms;
        }

        int maxMembers = limits.getMaxMembersPerRoom();
        if (maxMembers > 0) {
            CopyOnWriteArrayList<WebSocketSession> members = chatRooms.get(roomId);
            if (members != null && members.size() >= maxMembers) {
                return "Room is full (" + maxMembers + " members)";
            }
        }
        return null;
    }

    /**
     * Checks that a frame is consistent with the identity the session joined under.
     *
     * @return the reason to refuse the frame, or null when it is acceptable
     */
    private String identityProblem(WebSocketSession session, String roomId, ChatMessage message) {
        if (!identitySettings.isStrict()) {
            return null;
        }

        String claimed = message.getUsername();

        if (JOIN.equals(message.getMessageType())) {
            if (identitySettings.isUniqueUsernames() && isNameTakenInRoom(roomId, claimed)) {
                return "Username '" + claimed + "' is already in use in this room";
            }
            return null;
        }

        // Everything after JOIN must come from the identity that joined. Without
        // this a single connection can attribute messages to anyone it likes.
        String bound = boundUsername(session);
        if (bound != null && !bound.equals(claimed)) {
            return "Username does not match the session; joined as '" + bound + "'";
        }
        return null;
    }

    /**
     * @return the username this session joined with, or null if it has not joined
     */
    private String boundUsername(WebSocketSession session) {
        String id = session.getId();
        return id == null ? null : sessionUsernames.get(id);
    }

    /**
     * @return true when someone in the room already holds this username
     */
    private boolean isNameTakenInRoom(String roomId, String username) {
        return getRoomMembers(roomId).contains(username);
    }

    /**
     * Tells the rest of the room that someone is composing a message.
     *
     * @param roomId   -String, Representing the room
     * @param sender   -WebSocketSession, the composing session, which is skipped
     * @param username -String, who is typing
     */
    private void announceTyping(String roomId, WebSocketSession sender, String username) {
        metrics.recordTyping(sendToRoom(roomId, sender, TYPING_STATUS, username));
    }

    /**
     * Sends one frame to every session in a room.
     *
     * Delivery is best effort: one slow or dead peer must not fail the write for
     * anybody else, which is why each send is independent.
     *
     * @param roomId  -String, Representing the room
     * @param exclude -WebSocketSession, a session to skip, or null to include all
     * @param status  -String, the frame status
     * @param message -String, the frame body
     * @return how many sessions the frame was written to
     */
    private int sendToRoom(String roomId, WebSocketSession exclude, String status,
            String message) {
        CopyOnWriteArrayList<WebSocketSession> roomSessions = chatRooms.get(roomId);
        if (roomSessions == null) {
            return 0;
        }

        int recipients = 0;
        for (WebSocketSession peer : roomSessions) {
            if (exclude == null || !peer.equals(exclude)) {
                sendResponse(peer, status, message);
                recipients++;
            }
        }
        return recipients;
    }

    /**
     * Tells everyone in a room who is currently present.
     *
     * Sent to the whole room including the originator, because a client needs
     * the list on its own join, when no one else would tell it.
     *
     * @param roomId -String, Representing the room whose membership changed
     */
    private void announcePresence(String roomId) {
        CopyOnWriteArrayList<WebSocketSession> roomSessions = chatRooms.get(roomId);
        if (roomSessions == null || roomSessions.isEmpty()) {
            // nobody to tell, and building the member list would be wasted work
            return;
        }

        sendToRoom(roomId, null, PRESENCE, String.join(",", getRoomMembers(roomId)));
    }

    /**
     * Fans a message out to every joined session in the room except the originator.
     * Delivery is best effort: one slow or dead peer must not fail the sender's write.
     *
     * @param roomId  -String, Representing the Room ID
     * @param sender  -WebSocketSession, the session that produced the message
     * @param message -String, Representing the already formatted chat line
     */
    private void broadcast(String roomId, WebSocketSession sender, String message) {
        if (!broadcastEnabled) {
            return;
        }

        metrics.recordBroadcast(sendToRoom(roomId, sender, "BROADCAST", message));
    }

    /**
     * sending JSON response back to sender
     *
     * @param session -WebSocketSession, Representing the Session
     * @param status  -String, Representing the Status
     * @param message -String, Representing the msg
     */
    private void sendResponse(WebSocketSession session, String status, String message) {
        sendResponse(session, status, message, null);
    }

    /**
     * @param clientId correlation id supplied by the sender, echoed back so it
     *                 can tell which of its messages this reply answers; null
     *                 for unsolicited pushes, which answer nothing
     */
    private void sendResponse(WebSocketSession session, String status, String message,
            String clientId) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", status);
            response.put("serverTimestamp", Instant.now().toString());
            response.put("message", message);
            if (clientId != null && !clientId.isEmpty()) {
                response.put("clientId", clientId);
            }

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
        forgetUsername(session);
        // a dropped connection changes membership just as a LEAVE does
        announcePresence(roomId);
        // buckets are keyed by session id, so drop this one or the map grows forever
        if (session.getId() != null) {
            rateLimiters.remove(session.getId());
        }
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
     * Extracts the room id from the handshake URI.
     * A malformed or missing path must never break the connection lifecycle,
     * so anything unparseable is mapped to {@link #UNKNOWN_ROOM}.
     *
     * @param session -WebSocketSession, Representing the Session
     * @return -String, Representing the Room ID, never null
     */
    private String getRoomId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getPath() == null) {
            return UNKNOWN_ROOM;
        }

        String path = uri.getPath();
        int start = path.indexOf(ROOM_PATH_PREFIX);
        if (start < 0) {
            return UNKNOWN_ROOM;
        }

        String roomId = path.substring(start + ROOM_PATH_PREFIX.length());

        // keep only the first segment, so /chat/7/extra still resolves to room 7
        int nextSegment = roomId.indexOf('/');
        if (nextSegment >= 0) {
            roomId = roomId.substring(0, nextSegment);
        }

        return roomId.isBlank() ? UNKNOWN_ROOM : roomId;
    }
}