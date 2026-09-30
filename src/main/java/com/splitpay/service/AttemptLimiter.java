package com.splitpay.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Counts failed password attempts per key over a sliding window. */
@Component
public class AttemptLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new HashMap<>();
    private final Clock clock;

    public AttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized void check(String key, int maxFailures) {
        Deque<Instant> recent = prune(key);
        if (recent.size() >= maxFailures) {
            Duration wait = Duration.between(clock.instant(), recent.peekFirst().plus(WINDOW));
            long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".");
        }
    }

    public synchronized void fail(String key) {
        failures.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(clock.instant());
    }

    public synchronized void reset(String key) {
        failures.remove(key);
    }

    private Deque<Instant> prune(String key) {
        Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.removeFirst();
        }
        return recent;
    }
}
