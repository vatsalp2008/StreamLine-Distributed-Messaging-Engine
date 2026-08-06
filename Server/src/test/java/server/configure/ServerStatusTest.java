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
    private javax.sql.DataSource dataSource;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true);
        dataSource = mock(javax.sql.DataSource.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ServerStatus(handler, dataSource)).build();
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
    void readinessReportsReadyWhenTheDatabaseAnswers() throws Exception {
        java.sql.Connection connection = mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void readinessFailsWithServiceUnavailableWhenTheDatabaseIsDown() throws Exception {
        when(dataSource.getConnection())
                .thenThrow(new java.sql.SQLException("connection refused"));

        // a live but unusable instance must be taken out of rotation
        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }

    @Test
    void readinessFailsWhenTheConnectionIsInvalid() throws Exception {
        java.sql.Connection connection = mock(java.sql.Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("NOT_READY"));
    }

    @Test
    void livenessStaysUpEvenWhenTheDatabaseIsDown() throws Exception {
        when(dataSource.getConnection())
                .thenThrow(new java.sql.SQLException("connection refused"));

        // liveness answers "is the process alive", so it must not depend on I/O
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
