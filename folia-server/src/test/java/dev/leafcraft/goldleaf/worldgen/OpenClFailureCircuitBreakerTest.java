package dev.leafcraft.goldleaf.worldgen;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenClFailureCircuitBreakerTest {

    @Test
    void fatalDeviceLossTripsImmediately() {
        final AtomicLong clock = new AtomicLong(1_000L);
        final OpenClFailureCircuitBreaker breaker = new OpenClFailureCircuitBreaker(
            "test-fatal",
            3,
            10_000L,
            clock::get
        );

        assertEquals(OpenClFailureCircuitBreaker.ErrorKind.FATAL_DEVICE_LOSS,
            OpenClFailureCircuitBreaker.classifyErrorCode(OpenClFailureCircuitBreaker.CL_DEVICE_NOT_AVAILABLE));
        assertEquals("CL_DEVICE_NOT_AVAILABLE",
            OpenClFailureCircuitBreaker.describeErrorCode(OpenClFailureCircuitBreaker.CL_DEVICE_NOT_AVAILABLE));

        assertTrue(breaker.tryAcquireGpu());
        assertTrue(breaker.recordFailure(OpenClFailureCircuitBreaker.CL_DEVICE_NOT_AVAILABLE, "unit-test", null));
        assertEquals(OpenClFailureCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(1L, breaker.tripCount());
        assertFalse(breaker.tryAcquireGpu());
        assertEquals(1L, breaker.skippedDispatches());
    }

    @Test
    void transientFailuresTripAtThresholdAndRecoverViaHalfOpenProbe() {
        final AtomicLong clock = new AtomicLong(100_000L);
        final OpenClFailureCircuitBreaker breaker = new OpenClFailureCircuitBreaker(
            "test-transient",
            3,
            5_000L,
            clock::get
        );

        assertFalse(breaker.recordFailure(OpenClFailureCircuitBreaker.CL_OUT_OF_RESOURCES, "wave-1", null));
        assertEquals(OpenClFailureCircuitBreaker.State.CLOSED, breaker.state());
        assertFalse(breaker.recordFailure(OpenClFailureCircuitBreaker.CL_MEM_OBJECT_ALLOCATION_FAILURE, "wave-2", null));
        assertEquals(OpenClFailureCircuitBreaker.State.CLOSED, breaker.state());

        // Success resets streak
        breaker.recordSuccess();
        assertEquals(0, breaker.consecutiveFailures());

        // Three consecutive transient failures trip the breaker
        assertFalse(breaker.recordFailure(OpenClFailureCircuitBreaker.CL_OUT_OF_RESOURCES, "wave-3", null));
        assertFalse(breaker.recordFailure(OpenClFailureCircuitBreaker.CL_OUT_OF_RESOURCES, "wave-4", null));
        assertTrue(breaker.recordFailure(OpenClFailureCircuitBreaker.CL_OUT_OF_RESOURCES, "wave-5", null));
        assertEquals(OpenClFailureCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(1L, breaker.tripCount());

        // Bypassed during cooldown
        clock.addAndGet(4_999L);
        assertFalse(breaker.tryAcquireGpu());
        assertEquals(1L, breaker.skippedDispatches());

        // Cooldown elapsed: exactly one half-open probe allowed
        clock.addAndGet(1L);
        assertTrue(breaker.tryAcquireGpu());
        assertEquals(OpenClFailureCircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.tryAcquireGpu()); // second concurrent caller skipped

        // Half-open failure re-opens immediately
        assertTrue(breaker.recordFailure(OpenClFailureCircuitBreaker.CL_OUT_OF_RESOURCES, "probe-fail", null));
        assertEquals(OpenClFailureCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(2L, breaker.tripCount());

        // Cooldown again -> half-open probe succeeds -> closes breaker
        clock.addAndGet(5_000L);
        assertTrue(breaker.tryAcquireGpu());
        breaker.recordSuccess();
        assertEquals(OpenClFailureCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(0, breaker.consecutiveFailures());
        assertTrue(breaker.tryAcquireGpu());
    }

    @Test
    void extractsErrorCodesFromExceptionMessages() {
        assertEquals(-5, OpenClFailureCircuitBreaker.extractErrorCodeFromMessage(
            "OpenCL eval_biome_rtree_batch dispatch failed with error code -5"));
        assertEquals(-2, OpenClFailureCircuitBreaker.extractErrorCodeFromMessage(
            "OpenCL executeVramPipeline failed (err=-2)"));
        assertEquals(-999, OpenClFailureCircuitBreaker.extractErrorCodeFromMessage("generic runtime failure"));
        assertEquals(-999, OpenClFailureCircuitBreaker.extractErrorCodeFromMessage(null));

        final AtomicLong clock = new AtomicLong(0L);
        final OpenClFailureCircuitBreaker breaker = new OpenClFailureCircuitBreaker("msg", 3, 1_000L, clock::get);
        assertTrue(breaker.recordFailure("vram", new IllegalStateException("OpenCL executeVramPipeline failed (err=-36)")));
        assertEquals(OpenClFailureCircuitBreaker.State.OPEN, breaker.state());
        assertEquals("CL_INVALID_COMMAND_QUEUE", breaker.lastErrorName());
    }
}
