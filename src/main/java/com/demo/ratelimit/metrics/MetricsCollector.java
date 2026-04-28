package com.demo.ratelimit.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class MetricsCollector {

    private final Counter totalRequests;
    private final Counter allowedRequests;
    private final Counter throttledRequests;
    private final Timer requestLatency;

    public MetricsCollector(MeterRegistry meterRegistry) {
        this.totalRequests = Counter.builder("ratelimit.requests.total")
                .description("Total rate limit requests")
                .register(meterRegistry);

        this.allowedRequests = Counter.builder("ratelimit.requests.allowed")
                .description("Allowed rate limit requests")
                .register(meterRegistry);

        this.throttledRequests = Counter.builder("ratelimit.requests.throttled")
                .description("Throttled rate limit requests")
                .register(meterRegistry);

        this.requestLatency = Timer.builder("ratelimit.request.latency")
                .description("Rate limit request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public void recordRequest(boolean allowed) {
        totalRequests.increment();
        if (allowed) {
            allowedRequests.increment();
        } else {
            throttledRequests.increment();
        }
    }

    public Timer.Sample recordLatencyStart() {
        return Timer.start();
    }

    public void recordLatencyStop(Timer.Sample sample) {
        sample.stop(requestLatency);
    }
}