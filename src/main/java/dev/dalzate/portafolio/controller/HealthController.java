package dev.dalzate.portafolio.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

/**
 * Endpoint liviano para servicios externos de keep-alive (ej. cron-job.org),
 * que evitan que Render duerma la instancia por inactividad en el plan free.
 * No toca base de datos ni dependencias pesadas.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Value("${app.health.token}")
    private String expectedToken;

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping(
            @RequestHeader(value = "X-Health-Token", required = false) String token) {

        if (token == null || !constantTimeEquals(token, expectedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
