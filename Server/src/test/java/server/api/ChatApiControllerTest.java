package server.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import server.configure.ChatServerWSHandler;
import server.configure.StreamlineProperties;
import server.model.ChatMessage;
import server.service.ChatService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatApiControllerTest {

    private ChatServerWSHandler handler;
    private ChatService chatService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        chatService = mock(ChatService.class);
        handler = new ChatServerWSHandler(validator, mock(ChatService.class),
                new StreamlineProperties(), JsonMapper.builder().addModule(new JavaTimeModule()).build());

        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatApiController(handler, chatService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    private void join(String room, String username) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));

        handler.handleMessage(session, new TextMessage("""
                {"userId":1,"username":"%s","message":"Joining","timestamp":"2026-08-06T10:00:00Z","messageType":"JOIN"}
                """.formatted(username)));
    }

    private ChatMessage stored(String username, String text, String roomId) {
        ChatMessage msg = new ChatMessage();
        msg.setUsername(username);
        msg.setMessage(text);
        msg.setTimestamp(Instant.parse("2026-08-06T10:00:00Z"));
        msg.setRoomId(roomId);
        msg.setMessageType("TEXT");
        return msg;
    }

    // ---------- /api/rooms ----------

    @Test
    void roomsIsEmptyWhenNobodyHasJoined() throws Exception {
        mockMvc.perform(get("/api/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void roomsReportsMembersAndStoredCounts() throws Exception {
        join("general", "alice");
        join("general", "bob");
        join("random", "carol");
        when(chatService.countMessages("general")).thenReturn(12L);
        when(chatService.countMessages("random")).thenReturn(0L);

        mockMvc.perform(get("/api/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].roomId").value("general"))
                .andExpect(jsonPath("$[0].members").value(2))
                .andExpect(jsonPath("$[0].storedMessages").value(12))
                .andExpect(jsonPath("$[1].roomId").value("random"))
                .andExpect(jsonPath("$[1].members").value(1));
    }

    // ---------- /api/rooms/{id}/messages ----------

    @Test
    void messagesReturnsAPageOfHistory() throws Exception {
        Page<ChatMessage> page = new PageImpl<>(
                List.of(stored("alice", "hello", "general"), stored("bob", "hi", "general")),
                PageRequest.of(0, 50), 2);
        when(chatService.getMessagePage(eq("general"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/rooms/general/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("general"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].username").value("alice"))
                .andExpect(jsonPath("$.messages[0].message").value("hello"))
                .andExpect(jsonPath("$.totalMessages").value(2))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void messagesResponseOmitsInternalEntityFields() throws Exception {
        Page<ChatMessage> page = new PageImpl<>(
                List.of(stored("alice", "hello", "general")), PageRequest.of(0, 50), 1);
        when(chatService.getMessagePage(anyString(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/rooms/general/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].id").doesNotExist())
                .andExpect(jsonPath("$.messages[0].userId").doesNotExist());
    }

    @Test
    void pageSizeIsClampedToTheMaximum() throws Exception {
        Page<ChatMessage> empty = new PageImpl<>(List.of(), PageRequest.of(0, 200), 0);
        when(chatService.getMessagePage(anyString(), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get("/api/rooms/general/messages").param("size", "10000"))
                .andExpect(status().isOk());

        verify(chatService).getMessagePage("general",
                PageRequest.of(0, ChatApiController.MAX_PAGE_SIZE));
    }

    @Test
    void pageSizeBelowOneIsRaisedToOne() throws Exception {
        Page<ChatMessage> empty = new PageImpl<>(List.of(), PageRequest.of(0, 1), 0);
        when(chatService.getMessagePage(anyString(), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get("/api/rooms/general/messages").param("size", "0"))
                .andExpect(status().isOk());

        verify(chatService).getMessagePage("general", PageRequest.of(0, 1));
    }

    @Test
    void negativePageIsRejectedWithAStructuredError() throws Exception {
        mockMvc.perform(get("/api/rooms/general/messages").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("must not be negative")));
    }

    @Test
    void nonNumericPageIsRejectedWithAStructuredError() throws Exception {
        mockMvc.perform(get("/api/rooms/general/messages").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("page")));
    }

    @Test
    void unknownRoomReturnsAnEmptyPageRatherThanAnError() throws Exception {
        Page<ChatMessage> empty = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(chatService.getMessagePage(anyString(), any(Pageable.class))).thenReturn(empty);

        mockMvc.perform(get("/api/rooms/does-not-exist/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.totalMessages").value(0));
    }

    @Test
    void internalFailuresDoNotLeakDetailsToTheCaller() throws Exception {
        when(chatService.getMessagePage(anyString(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("connection pool exhausted at 10.0.0.4"));

        mockMvc.perform(get("/api/rooms/general/messages"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("The request could not be completed"));
    }
}
