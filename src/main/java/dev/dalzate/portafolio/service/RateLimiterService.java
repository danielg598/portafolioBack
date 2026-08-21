package dev.dalzate.portafolio.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter por IP.
 * Permite `capacity` peticiones cada `refillMinutes` minutos.
 */
@Service
public class RateLimiterService {

    @Value("${app.rate-limit.capacity:5}")
    private int capacity;

    @Value("${app.rate-limit.refill-minutes:10}")
    private int refillMinutes;

    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public boolean isAllowed(String ip) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds((long) refillMinutes * 60);

        Deque<Instant> timestamps = windows.computeIfAbsent(ip, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= capacity) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
