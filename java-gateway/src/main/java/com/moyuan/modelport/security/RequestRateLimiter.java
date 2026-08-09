package com.moyuan.modelport.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RequestRateLimiter {
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RequestRateLimiter() {
        this(Clock.systemUTC());
    }

    RequestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String keyId, int limit) {
        if (limit <= 0) return false;
        long now = clock.millis();
        long cutoff = now - 60_000;
        Deque<Long> window = windows.computeIfAbsent(keyId, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= cutoff) window.removeFirst();
            if (window.size() >= limit) return false;
            window.addLast(now);
            return true;
        }
    }
}
