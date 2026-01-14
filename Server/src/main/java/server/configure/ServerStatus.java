package server.configure;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;

@RestController
public class ServerStatus {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> serverStatus() {
        return ResponseEntity.ok(Map.of("status", "RUNNING"));
    }
}