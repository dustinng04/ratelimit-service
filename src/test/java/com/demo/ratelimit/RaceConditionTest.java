package com.demo.ratelimit;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import com.demo.ratelimit.strategy.NonAtomicRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RaceConditionTest demonstrates race conditions with NonAtomicRateLimiter.
 * Shows that under concurrent load, non-atomic operations allow MORE requests than the limit.
 * This is the "bug" that atomic Lua scripts fix.
 */
@SpringBootTest
@ActiveProfiles("test")
class RaceConditionTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private NonAtomicRateLimiter rateLimiter;
    private QuotaConfig quotaConfig;
    private static final String TEST_KEY = "race-test-key";
    private static final int LIMIT = 10;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    @BeforeEach
    void setUp() {
        quotaConfig = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, LIMIT, WINDOW);
        rateLimiter = new NonAtomicRateLimiter(redisTemplate, quotaConfig);
        redisTemplate.delete(generateRedisKey(TEST_KEY));
    }

    /**
     * Demonstrates the race condition: with 100 concurrent threads,
     * we expect only 10 to pass (the limit), but we typically see 20+ passing.
     * This proves the GET-then-INCR race condition allows double-counting.
     */
    @Test
    void demonstrateRaceConditionWithConcurrentRequests() throws InterruptedException {
        int numThreads = 100;
        int numRuns = 5;

        System.out.println("\n=== RACE CONDITION DEMO ===");
        System.out.println("Config: limit=" + LIMIT + ", threads=" + numThreads);
        System.out.println("Running " + numRuns + " iterations to show non-determinism\n");

        for (int run = 0; run < numRuns; run++) {
            redisTemplate.delete(generateRedisKey(TEST_KEY));

            AtomicInteger allowedCount = new AtomicInteger(0);
            AtomicInteger deniedCount = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(numThreads);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            // Launch all threads, make them wait at barrier, then run simultaneously
            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for signal to start
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    RateLimitResponse response = rateLimiter.getRateLimit(TEST_KEY);
                    if (response.isAllowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                    endLatch.countDown();
                });
            }

            startLatch.countDown(); // Signal all threads to go
            endLatch.await();
            executor.shutdown();

            String actualRedisCount = redisTemplate.opsForValue().get(generateRedisKey(TEST_KEY));
            long redisCounter = actualRedisCount != null ? Long.parseLong(actualRedisCount) : 0;

            System.out.printf("Run %d: Allowed=%d, Denied=%d, Redis Counter=%d, Overflow=%.1f%%%n",
                    run + 1,
                    allowedCount.get(),
                    deniedCount.get(),
                    redisCounter,
                    ((double) (allowedCount.get() - LIMIT) / LIMIT * 100));

            // Verify the bug: allowed count > limit (should never happen with atomic ops)
            assertTrue(allowedCount.get() > LIMIT,
                    "Expected more than " + LIMIT + " allowed requests due to race condition");
        }
        System.out.println("\n✗ RESULT: Race condition confirmed! Allowed count exceeds limit.\n");
    }

    /**
     * Measure consistency across runs.
     * Without atomicity, results vary significantly between runs (non-deterministic).
     */
    @Test
    void measureConsistencyIssue() throws InterruptedException {
        int numThreads = 50;
        int numRuns = 10;
        int[] allowedCounts = new int[numRuns];

        System.out.println("\n=== CONSISTENCY CHECK (showing variance) ===");

        for (int run = 0; run < numRuns; run++) {
            redisTemplate.delete(generateRedisKey(TEST_KEY));

            AtomicInteger allowed = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(numThreads);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (rateLimiter.isAllowed(TEST_KEY)) {
                        allowed.incrementAndGet();
                    }
                    endLatch.countDown();
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            allowedCounts[run] = allowed.get();
            System.out.println("Run " + (run + 1) + ": " + allowed.get() + " allowed");
        }

        // Calculate variance
        double avg = Arrays.stream(allowedCounts).average().orElse(0);
        double variance = Arrays.stream(allowedCounts)
                .mapToDouble(x -> Math.pow(x - avg, 2))
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);

        System.out.printf("\nAverage: %.1f, StdDev: %.1f, Min: %d, Max: %d%n",
                avg, stdDev,
                Arrays.stream(allowedCounts).min().orElse(0),
                Arrays.stream(allowedCounts).max().orElse(0));
        System.out.println("✗ High variance = non-deterministic behavior (race condition)\n");
    }

    private String generateRedisKey(String key) {
        long windowStart = (System.currentTimeMillis() / 1000 / WINDOW.toSeconds()) * WINDOW.toSeconds();
        return "ratelimit:nonatomic:" + key + ":" + windowStart;
    }
}

