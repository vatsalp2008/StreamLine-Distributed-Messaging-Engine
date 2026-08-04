package server.configure;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import server.service.ChatService;

import java.io.IOException;
import java.net.URI;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServerStatusTest {

    private ChatServerWSHandler handler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true);
        mockMvc = MockMvcBuilders.standaloneSetup(new ServerStatus(handler)).build();
    }

    private void join(String room, String username) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:8080/chat/" + room));

        handler.handleMessage(session, new TextMessage("""
                {"userId":1,"username":"%s","message":"Joining","timestamp":"2026-08-04T10:00:00Z","messageType":"JOIN"}
                """.formatted(username)));
    }

    @Test
    void healthReportsRunning() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void statsAreEmptyBeforeAnyoneJoins() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRooms").value(0))
                .andExpect(jsonPath("$.joinedSessions").value(0))
                .andExpect(jsonPath("$.roomOccupancy").isEmpty());
    }

    @Test
    void statsCountRoomsAndMembers() throws Exception {
        join("1", "alice");
        join("1", "bob");
        join("2", "carol");

        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRooms").value(2))
                .andExpect(jsonPath("$.joinedSessions").value(3))
                .andExpect(jsonPath("$.roomOccupancy.1").value(2))
                .andExpect(jsonPath("$.roomOccupancy.2").value(1));
    }
}
