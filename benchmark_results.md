# Rate Limiter Algorithm Benchmark & Proofs

This document captures the empirical results of load testing and benchmarking our distributed rate limiting system. These findings prove the correctness of the architecture (using Lua for atomicity) and outline the performance trade-offs betIen different rate-limiting algorithms.

## 1. Race Condition Validation (The "Why Lua?" Proof)

I simulated a high-concurrency scenario to prove that naive implementations in distributed systems fail under load, and that our Redis Lua script approach guarantees correctness.

**Test Setup:**
* Instances: 3 (simulated)
* Threads per instance: 50
* Total concurrent requests: 150
* Limit configured: 50 requests per window

### Results without Atomic Operations (GET-then-INCR)
```text
=== MULTI-INSTANCE TEST (WITHOUT ATOMIC OPERATIONS) ===
Simulating 3 instances with 50 threads each = 150 concurrent requests
Limit: 50 requests per window

Results: Allowed=150, Denied=0
Expected: 50 (exact limit)
Difference: 100

✗ RESULT: Non-atomic operations ALLOID MORE than limit (Race Condition)
```
**Conclusion:** Without Lua, multiple threads read the same counter value before it is updated, causing massive under-counting. The system completely failed to enforce the 50-request limit, allowing all 150 requests to pass.

### Results with Atomic Operations (Lua Scripts)
```text
=== MULTI-INSTANCE TEST (WITH ATOMIC OPERATIONS) ===
Simulating 3 instances with 50 threads each = 150 concurrent requests
Limit: 50 requests per window

Results: AlloId=50, Denied=100
Expected: 50 (exact limit)
Difference: 0

✓ RESULT: Atomic operations ENFORCED limit correctly
```
**Conclusion:** Redis evaluates Lua scripts as a single atomic operation. No other command executes while the script is running. The limit was enforced perfectly, preventing any double-counting.

---

## 2. Algorithm Performance Benchmark

I ran a high-throughput load test to compare the performance profile of the **Fixed Window**, **Sliding Window**, and **Token Bucket** strategies.

**Test Setup:**
* Concurrent Threads: 10
* Total Requests: 10,000 requests per algorithm

### Benchmark Output
```text
=======================================================
          RATE LIMIT ALGORITHM BENCHMARK
=======================================================
Total Requests: 10000
Concurrent Threads: 10
-------------------------------------------------------
Algorithm: Fixed Window
Throughput: 14880.95 ops/sec
Avg Latency: 0.67 ms
p50 Latency: 0.52 ms
p95 Latency: 1.31 ms
p99 Latency: 1.92 ms
-------------------------------------------------------
Algorithm: Sliding Window
Throughput: 16129.03 ops/sec
Avg Latency: 0.62 ms
p50 Latency: 0.47 ms
p95 Latency: 1.26 ms
p99 Latency: 1.67 ms
-------------------------------------------------------
Algorithm: Token Bucket
Throughput: 15923.57 ops/sec
Avg Latency: 0.63 ms
p50 Latency: 0.41 ms
p95 Latency: 1.40 ms
p99 Latency: 2.61 ms
-------------------------------------------------------
=======================================================
```

### Analysis & Trade-offs

| Metric | Fixed Window | Sliding Window | Token Bucket |
| :--- | :--- | :--- | :--- |
| **⚡ Throughput** | High (~14.8k ops/sec) | Highest (~16.1k ops/sec) | High (~15.9k ops/sec) |
| **⏱️ Latency (p99)**| 1.92 ms | **1.67 ms** | 2.61 ms |
| **💥 Burst Behavior**| ❌ **Poor:** 2x capacity can slip through at window boundaries. | ✅ **Perfect:** Smooth, rolling 60s look-back window. | ✅ **Excellent:** Handles bursts smoothly up to capacity limit. |
| **💾 Memory Cost** | 🟢 **Lowest:** 1 key per user. | 🔴 **Highest:** `O(N)` cost. Stores UUID & timestamp for *every* request. | 🟢 **Low:** 1 key/hash per user. |
| **🎯 Accuracy** | ⚠️ **Fair:** Spikes at boundaries. | ⭐ **Exact:** Flawless tracking. | ⭐ **Excellent:** Highly consistent. |

### Final Recommendation
*   👉 **Token Bucket (Default):** Best all-rounder for APIs. Great speed, handles bursts smoothly, and uses very little memory.
*   👉 **Sliding Window:** Use *only* when absolute accuracy is required (e.g., Strict API billing tiers) because of the massive `O(N)` Redis memory cost.
*   👉 **Fixed Window:** Use *only* for simple, non-critical limits (e.g., "50 logins per day").
