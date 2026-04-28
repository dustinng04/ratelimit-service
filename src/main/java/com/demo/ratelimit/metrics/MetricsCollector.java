package com.demo.ratelimit.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class MetricsCollector {
    private final MeterRegistry meterRegistry;

    public MetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordRequest(String strategy, String key, boolean allowed) {
        Counter.builder("ratelimit.requests.total")
                .description("Total rate limit requests")
                .tag("strategy", strategy)
                .tag("key", key)
                .register(meterRegistry)
                .increment();

        if (allowed) {
            Counter.builder("ratelimit.requests.allowed")
                    .description("Allowed rate limit requests")
                    .tag("strategy", strategy)
                    .tag("key", key)
                    .register(meterRegistry)
                    .increment();
        } else {
            Counter.builder("ratelimit.requests.throttled")
                    .description("Throttled rate limit requests")
                    .tag("strategy", strategy)
                    .tag("key", key)
                    .register(meterRegistry)
                    .increment();
        }
    }

    public Timer.Sample recordLatencyStart() {
        return Timer.start();
    }

    public void recordLatencyStop(Timer.Sample sample, String strategy, String key) {
        Timer timer = Timer.builder("ratelimit.request.latency")
                .description("Rate limit request latency")
                .tag("strategy", strategy)
                .tag("key", key)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        sample.stop(timer);
    }
}