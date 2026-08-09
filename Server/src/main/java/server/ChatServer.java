package server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Class ChatServer (@SpringBootApplication)
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        server.configure.StreamlineProperties.class)
public class ChatServer {
    public static void main(String[] args) {
        SpringApplication.run(ChatServer.class, args);
    }
}
