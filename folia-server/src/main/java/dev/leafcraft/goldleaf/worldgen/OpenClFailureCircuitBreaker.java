package dev.leafcraft.goldleaf.worldgen;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consecutive-failure and fatal-fault circuit breaker for OpenCL GPU dispatches
 * ({@code goldleaf-ADR-004}, {@code goldleaf-ADR-017}, {@code S-004}).
 *
 * <p>Classifies raw OpenCL {@code cl_int} error codes returned by {@code libgoldleaf_opencl.so}:
 * fatal device/driver/context loss codes trip immediately, while transient resource/OOM or runtime
 * errors trip after {@code consecutiveFailureThreshold} consecutive failures. While open, callers
 * bypass the GPU directly to CPU fallback without retry storms or per-wave {@code WARN} spam; once
 * the cooldown elapses, a single half-open probe is permitted to test recovery.</p>
 */
public final class OpenClFailureCircuitBreaker {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenClFailureCircuitBreaker.class);

    public static final int CL_SUCCESS = 0;
    public static final int CL_DEVICE_NOT_FOUND = -1;
    public static final int CL_DEVICE_NOT_AVAILABLE = -2;
    public static final int CL_COMPILER_NOT_AVAILABLE = -3;
    public static final int CL_MEM_OBJECT_ALLOCATION_FAILURE = -4;
    public static final int CL_OUT_OF_RESOURCES = -5;
    public static final int CL_OUT_OF_HOST_MEMORY = -6;
    public static final int CL_INVALID_VALUE = -30;
    public static final int CL_INVALID_DEVICE_TYPE = -31;
    public static final int CL_INVALID_PLATFORM = -32;
    public static final int CL_INVALID_DEVICE = -33;
    public static final int CL_INVALID_CONTEXT = -34;
    public static final int CL_INVALID_COMMAND_QUEUE = -36;
    public static final int CL_INVALID_MEM_OBJECT = -38;
    public static final int CL_INVALID_KERNEL = -48;
    public static final int CL_INVALID_WORK_GROUP_SIZE = -54;

    public static final int DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD = 3;
    public static final long DEFAULT_COOLDOWN_NANOS = 30_000_000_000L; // 30 seconds

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public enum ErrorKind {
        FATAL_DEVICE_LOSS,
        TRANSIENT_RESOURCE,
        OTHER
    }

    private final String componentName;
    private final int consecutiveFailureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoClock;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openUntilNanos = new AtomicLong(0L);
    private final AtomicLong tripCount = new AtomicLong(0L);
    private final AtomicLong skippedDispatches = new AtomicLong(0L);
    private final AtomicInteger lastErrorCode = new AtomicInteger(CL_SUCCESS);

    public OpenClFailureCircuitBreaker(final String componentName) {
        this(componentName, DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_NANOS, System::nanoTime);
    }

    public OpenClFailureCircuitBreaker(
        final String componentName,
        final int consecutiveFailureThreshold,
        final long cooldownNanos,
        final LongSupplier nanoClock
    ) {
        this.componentName = Objects.requireNonNull(componentName, "componentName");
        if (consecutiveFailureThreshold < 1) {
            throw new IllegalArgumentException("consecutiveFailureThreshold must be >= 1, got " + consecutiveFailureThreshold);
        }
        if (cooldownNanos < 0L) {
            throw new IllegalArgumentException("cooldownNanos must be >= 0, got " + cooldownNanos);
        }
        this.consecutiveFailureThreshold = consecutiveFailureThreshold;
        this.cooldownNanos = cooldownNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public static ErrorKind classifyErrorCode(final int clErr) {
        return switch (clErr) {
            case CL_DEVICE_NOT_FOUND,
                CL_DEVICE_NOT_AVAILABLE,
                CL_COMPILER_NOT_AVAILABLE,
                CL_INVALID_DEVICE,
                CL_INVALID_CONTEXT,
                CL_INVALID_COMMAND_QUEUE -> ErrorKind.FATAL_DEVICE_LOSS;
            case CL_MEM_OBJECT_ALLOCATION_FAILURE,
                CL_OUT_OF_RESOURCES,
                CL_OUT_OF_HOST_MEMORY -> ErrorKind.TRANSIENT_RESOURCE;
            default -> ErrorKind.OTHER;
        };
    }

    public static String describeErrorCode(final int clErr) {
        return switch (clErr) {
            case CL_SUCCESS -> "CL_SUCCESS";
            case CL_DEVICE_NOT_FOUND -> "CL_DEVICE_NOT_FOUND";
            case CL_DEVICE_NOT_AVAILABLE -> "CL_DEVICE_NOT_AVAILABLE";
            case CL_COMPILER_NOT_AVAILABLE -> "CL_COMPILER_NOT_AVAILABLE";
            case CL_MEM_OBJECT_ALLOCATION_FAILURE -> "CL_MEM_OBJECT_ALLOCATION_FAILURE";
            case CL_OUT_OF_RESOURCES -> "CL_OUT_OF_RESOURCES";
            case CL_OUT_OF_HOST_MEMORY -> "CL_OUT_OF_HOST_MEMORY";
            case CL_INVALID_VALUE -> "CL_INVALID_VALUE";
            case CL_INVALID_DEVICE_TYPE -> "CL_INVALID_DEVICE_TYPE";
            case CL_INVALID_PLATFORM -> "CL_INVALID_PLATFORM";
            case CL_INVALID_DEVICE -> "CL_INVALID_DEVICE";
            case CL_INVALID_CONTEXT -> "CL_INVALID_CONTEXT";
            case CL_INVALID_COMMAND_QUEUE -> "CL_INVALID_COMMAND_QUEUE";
            case CL_INVALID_MEM_OBJECT -> "CL_INVALID_MEM_OBJECT";
            case CL_INVALID_KERNEL -> "CL_INVALID_KERNEL";
            case CL_INVALID_WORK_GROUP_SIZE -> "CL_INVALID_WORK_GROUP_SIZE";
            default -> "CL_ERROR(" + clErr + ")";
        };
    }

    /**
     * Attempts to permit a GPU dispatch. Returns {@code false} while the breaker is open (or a
     * single half-open probe is already in flight), instructing the caller to fall back to CPU.
     */
    public boolean tryAcquireGpu() {
        final State current = this.state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            final long now = this.nanoClock.getAsLong();
            if (now - this.openUntilNanos.get() >= 0L) {
                if (this.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    return true;
                }
            }
        }
        this.skippedDispatches.incrementAndGet();
        return false;
    }

    /**
     * Records a successful GPU dispatch, resetting consecutive failures and closing the breaker.
     */
    public void recordSuccess() {
        this.consecutiveFailures.set(0);
        final State prev = this.state.getAndSet(State.CLOSED);
        if (prev == State.HALF_OPEN) {
            LOGGER.info(
                "[GoldLeaf-OpenCL] Circuit breaker '{}' recovered on half-open probe; GPU dispatch resumed.",
                this.componentName
            );
        }
    }

    /**
     * Records a GPU dispatch failure with a raw OpenCL {@code cl_int} error code.
     *
     * @return {@code true} if this failure tripped or re-opened the circuit breaker
     */
    public boolean recordFailure(final int clErrorCode, final String context, final @Nullable Throwable cause) {
        this.lastErrorCode.set(clErrorCode);
        final ErrorKind kind = classifyErrorCode(clErrorCode);
        final int streak = this.consecutiveFailures.incrementAndGet();
        final boolean wasHalfOpen = this.state.get() == State.HALF_OPEN;
        final boolean shouldTrip = wasHalfOpen
            || kind == ErrorKind.FATAL_DEVICE_LOSS
            || streak >= this.consecutiveFailureThreshold;

        if (shouldTrip) {
            this.openUntilNanos.set(this.nanoClock.getAsLong() + this.cooldownNanos);
            final State prev = this.state.getAndSet(State.OPEN);
            if (prev != State.OPEN) {
                final long trips = this.tripCount.incrementAndGet();
                LOGGER.warn(
                    "[GoldLeaf-OpenCL S-004] Circuit breaker '{}' tripped (trip #{}, kind={}, code={} ({}), streak={}, cooldownMs={}) during {}; demoting to CPU fallback",
                    this.componentName,
                    trips,
                    kind,
                    describeErrorCode(clErrorCode),
                    clErrorCode,
                    streak,
                    this.cooldownNanos / 1_000_000L,
                    context,
                    cause
                );
                return true;
            }
        }
        return false;
    }

    /**
     * Records a GPU dispatch failure from a Java exception (extracting an OpenCL error code if present).
     *
     * @return {@code true} if this failure tripped or re-opened the circuit breaker
     */
    public boolean recordFailure(final String context, final Throwable cause) {
        final int extracted = extractErrorCodeFromMessage(cause != null ? cause.getMessage() : null);
        return recordFailure(extracted, context, cause);
    }

    static int extractErrorCodeFromMessage(final @Nullable String message) {
        if (message == null || message.isEmpty()) {
            return -999;
        }
        int start = -1;
        final int errorCodeIdx = message.lastIndexOf("error code ");
        if (errorCodeIdx >= 0) {
            start = errorCodeIdx + "error code ".length();
        } else {
            final int errEqIdx = message.lastIndexOf("err=");
            if (errEqIdx >= 0) {
                start = errEqIdx + "err=".length();
            }
        }
        if (start < 0) {
            return -999;
        }
        int end = start;
        if (end < message.length() && message.charAt(end) == '-') {
            end++;
        }
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end <= start || (end == start + 1 && message.charAt(start) == '-')) {
            return -999;
        }
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (final NumberFormatException ignored) {
            return -999;
        }
    }

    public State state() {
        return this.state.get();
    }

    public int consecutiveFailures() {
        return this.consecutiveFailures.get();
    }

    public long tripCount() {
        return this.tripCount.get();
    }

    public long skippedDispatches() {
        return this.skippedDispatches.get();
    }

    public int lastErrorCode() {
        return this.lastErrorCode.get();
    }

    public String lastErrorName() {
        return describeErrorCode(this.lastErrorCode.get());
    }
}
