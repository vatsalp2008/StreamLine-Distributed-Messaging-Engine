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
    private StreamlineProperties properties;
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor persistencePool;

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    /** Rebuilds the endpoint with a token file in place. */
    private void withTokenFile(String contents) throws IOException {
        java.nio.file.Path file = tempDir.resolve("tokens.properties");
        java.nio.file.Files.writeString(file, contents);
        properties.getAuth().setRoomTokenFile(file.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ServerStatus(handler, dataSource, properties, persistencePool,
                        new RoomTokenStore(properties))).build();
    }

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        handler = new ChatServerWSHandler(validator, mock(ChatService.class), true);
        dataSource = mock(javax.sql.DataSource.class);
        properties = new StreamlineProperties();
        persistencePool = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        persistencePool.setCorePoolSize(1);
        persistencePool.setQueueCapacity(4);
        persistencePool.initialize();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ServerStatus(handler, dataSource, properties, persistencePool,
                        new RoomTokenStore(properties))).build();
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

    @Test
    void statsReportTheConfiguredCaps() throws Exception {
        properties.getLimits().setMaxRooms(250);
        properties.getLimits().setMaxMembersPerRoom(40);
        properties.getRetention().setDays(30);

        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits.maxRooms").value(250))
                .andExpect(jsonPath("$.limits.maxMembersPerRoom").value(40))
                .andExpect(jsonPath("$.limits.retentionDays").value(30));
    }

    @Test
    void unlimitedCapsAreReportedAsZero() throws Exception {
        properties.getLimits().setMaxRooms(0);

        mockMvc.perform(get("/stats"))
                .andExpect(jsonPath("$.limits.maxRooms").value(0));
    }

    @Test
    void statsReportHowFullTheWriteQueueIs() throws Exception {
        // an idle server has nothing queued
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeQueueSaturation").value(0.0));
    }

    @Test
    void aBacklogOfWritesShowsAsSaturation() throws Exception {
        java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        persistencePool.execute(() -> {
            running.countDown();
            try {
                block.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        running.await(10, java.util.concurrent.TimeUnit.SECONDS);
        persistencePool.execute(() -> { });
        persistencePool.execute(() -> { });

        // 2 queued of a capacity of 4; writes run on the caller once it fills
        mockMvc.perform(get("/stats"))
                .andExpect(jsonPath("$.writeQueueSaturation").value(0.5));

        block.countDown();
    }

    // ---------- room token rotation ----------

    @Test
    void statsSayWhenNoTokenFileIsConfigured() throws Exception {
        mockMvc.perform(get("/stats"))
                .andExpect(jsonPath("$.roomTokens.fileConfigured").value(false))
                .andExpect(jsonPath("$.roomTokens.roomsFromFile").value(0));
    }

    @Test
    void statsReportHowManyRoomsCameFromTheFile() throws Exception {
        withTokenFile("alpha=one\nbeta=two\n");

        mockMvc.perform(get("/stats"))
                .andExpect(jsonPath("$.roomTokens.fileConfigured").value(true))
                .andExpect(jsonPath("$.roomTokens.roomsFromFile").value(2))
                .andExpect(jsonPath("$.roomTokens.lastError").doesNotExist());
    }

    @Test
    void anUnreadableFileIsReportedRatherThanLookingEmpty() throws Exception {
        properties.getAuth().setRoomTokenFile(tempDir.resolve("missing.properties").toString());
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ServerStatus(handler, dataSource, properties, persistencePool,
                        new RoomTokenStore(properties))).build();

        // otherwise "0 rooms" reads the same as a file that was never read
        mockMvc.perform(get("/stats"))
                .andExpect(jsonPath("$.roomTokens.fileConfigured").value(true))
                .andExpect(jsonPath("$.roomTokens.lastError").isNotEmpty());
    }
}
