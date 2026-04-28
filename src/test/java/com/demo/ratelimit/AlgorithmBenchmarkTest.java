package com.demo.ratelimit;

import com.demo.ratelimit.service.dto.QuotaConfig;
import com.demo.ratelimit.service.dto.RateLimitResponse;
import com.demo.ratelimit.strategy.FixedWindowStrategy;
import com.demo.ratelimit.strategy.RateLimitStrategy;
import com.demo.ratelimit.strategy.SlidingWindowStrategy;
import com.demo.ratelimit.strategy.TokenBucketStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
@ActiveProfiles("test")
public class AlgorithmBenchmarkTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final int THREADS = 10;
    private static final int REQUESTS_PER_THREAD = 1000;
    private static final int TOTAL_REQUESTS = THREADS * REQUESTS_PER_THREAD;

    @Test
    void benchmarkAlgorithms() throws InterruptedException {
        System.out.println("\n=======================================================");
        System.out.println("          RATE LIMIT ALGORITHM BENCHMARK");
        System.out.println("=======================================================");
        System.out.println("Total Requests: " + TOTAL_REQUESTS);
        System.out.println("Concurrent Threads: " + THREADS);
        System.out.println("-------------------------------------------------------");

        QuotaConfig config = new QuotaConfig(QuotaConfig.Strategy.FIXED_WINDOW, TOTAL_REQUESTS * 2, Duration.ofSeconds(300));
        QuotaConfig slidingConfig = new QuotaConfig(QuotaConfig.Strategy.SLIDING_WINDOW, TOTAL_REQUESTS * 2, Duration.ofSeconds(300));
        
        RateLimitStrategy fixedWindow = new FixedWindowStrategy(redisTemplate, config);
        RateLimitStrategy tokenBucket = new TokenBucketStrategy(redisTemplate, config);
        RateLimitStrategy slidingWindow = new SlidingWindowStrategy(redisTemplate, slidingConfig);

        runBenchmark("Fixed Window", fixedWindow);
        runBenchmark("Sliding Window", slidingWindow);
        runBenchmark("Token Bucket", tokenBucket);
        
        System.out.println("=======================================================\n");
    }

    private void runBenchmark(String name, RateLimitStrategy strategy) throws InterruptedException {
        String key = "benchmark:" + name.replace(" ", "") + ":" + System.currentTimeMillis();
        
        // Warmup
        for (int i = 0; i < 100; i++) {
            strategy.getRateLimit(key);
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(TOTAL_REQUESTS);
        
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(TOTAL_REQUESTS));

        for (int t = 0; t < THREADS; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                    long start = System.nanoTime();
                    RateLimitResponse response = strategy.getRateLimit(key);
                    long duration = System.nanoTime() - start;
                    latencies.add(duration);
                    endLatch.countDown();
                }
            });
        }

        long testStartTime = System.currentTimeMillis();
        startLatch.countDown();
        endLatch.await();
        long totalTimeMs = System.currentTimeMillis() - testStartTime;
        executor.shutdown();

        // Calculate metrics
        Collections.sort(latencies);
        
        long sum = 0;
        for (Long latency : latencies) {
            sum += latency;
        }
        
        double avgLatencyMs = (sum / (double) latencies.size()) / 1_000_000.0;
        double p50Ms = latencies.get(latencies.size() / 2) / 1_000_000.0;
        double p95Ms = latencies.get((int) (latencies.size() * 0.95)) / 1_000_000.0;
        double p99Ms = latencies.get((int) (latencies.size() * 0.99)) / 1_000_000.0;
        
        double throughput = (TOTAL_REQUESTS / (double) totalTimeMs) * 1000.0;

        System.out.printf("Algorithm: %s%n", name);
        System.out.printf("Throughput: %.2f ops/sec%n", throughput);
        System.out.printf("Avg Latency: %.2f ms%n", avgLatencyMs);
        System.out.printf("p50 Latency: %.2f ms%n", p50Ms);
        System.out.printf("p95 Latency: %.2f ms%n", p95Ms);
        System.out.printf("p99 Latency: %.2f ms%n", p99Ms);
        System.out.println("-------------------------------------------------------");
    }
}
