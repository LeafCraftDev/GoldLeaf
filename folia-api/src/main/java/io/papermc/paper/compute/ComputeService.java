package io.papermc.paper.compute;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Public plugin-facing GPU/CPU compute offload SPI ({@code goldleaf-ADR-004},
 * {@code goldleaf-ADR-017}).
 *
 * <p>Allows plugins to execute audited built-in compute kernels (such as
 * {@link BuiltInKernels#MASS_EXPLOSION_RAYCAST}) or admin-allowlisted custom OpenCL C99 kernels
 * off-tick ({@code S-005}) with automatic CPU fallback, strict VRAM quotas ({@code S-002}), and
 * optional {@code 1..20 Hz} latest-frame-wins paced execution.</p>
 *
 * <p>Plugins may discover this service via {@link #get()}.</p>
 */
public interface ComputeService {

    /**
     * Minimum supported cadence (in Hz) for a {@link PacedSession}.
     */
    int MIN_TARGET_HZ = 1;

    /**
     * Maximum supported cadence (in Hz) for a {@link PacedSession}.
     */
    int MAX_TARGET_HZ = 20;

    /**
     * Execution backend used to satisfy a compute dispatch.
     */
    enum ExecutionBackend {
        /**
         * Dispatched on a native OpenCL GPU device.
         */
        OPENCL_GPU,
        /**
         * Executed via the mandatory CPU fallback implementation.
         */
        CPU_FALLBACK
    }

    /**
     * Built-in audited kernel identifiers shipped by the server.
     */
    final class BuiltInKernels {
        /**
         * Batched vanilla 16x16x16 cube-surface (1,352 rays per explosion) mass explosion
         * raycaster producing a compact destroyed-block bitset over an immutable blast-resistance
         * grid snapshot.
         */
        public static final String MASS_EXPLOSION_RAYCAST = "paper:mass_explosion_raycast";

        private BuiltInKernels() {
        }
    }

    /**
     * Mandatory CPU fallback executor invoked whenever OpenCL GPU acceleration is unavailable,
     * disabled, or bypassed.
     */
    @FunctionalInterface
    interface CpuKernelExecutor {
        /**
         * Executes the kernel on CPU using the same buffer layout and scalar parameters as the
         * OpenCL kernel.
         *
         * @param input      read-only direct/heap input buffer ({@code inputBytes} readable bytes)
         * @param state      optional persistent state buffer ({@code null} if {@code persistentStateBytes == 0})
         * @param output     caller-owned output buffer to populate ({@code outputBytes} writable bytes)
         * @param params     scalar integer parameters
         * @param globalWork total 1D work-item count
         */
        void execute(
            ByteBuffer input,
            @Nullable ByteBuffer state,
            ByteBuffer output,
            int[] params,
            int globalWork
        );
    }

    /**
     * Immutable descriptor for registering a custom OpenCL C99 compute kernel.
     *
     * <p>Custom kernels must adhere to the packed-kernel ABI:
     * {@code __kernel void <kernelName>(__global const uchar* in_buf, __global uchar* state_buf,
     * __global uchar* out_buf, __constant const int* params, const int param_count,
     * const int global_items)}.</p>
     *
     * @param ownerId              owning plugin or subsystem identifier used for VRAM quota and teardown
     * @param kernelId             unique kernel identifier within the owner namespace
     * @param kernelName           OpenCL C99 {@code __kernel} entry-point function name
     * @param openClSource         OpenCL C99 source code
     * @param buildOptions         optional OpenCL compiler build options string
     * @param maxInputBytes        maximum input buffer capacity in bytes (must be {@code >= 0})
     * @param maxOutputBytes       maximum output buffer capacity in bytes (must be {@code > 0})
     * @param persistentStateBytes persistent device state buffer size in bytes (must be {@code >= 0})
     * @param maxGlobalWorkItems   maximum 1D global work-item count per dispatch (must be {@code > 0})
     * @param cpuFallback          mandatory CPU fallback executor
     */
    record KernelDescriptor(
        String ownerId,
        String kernelId,
        String kernelName,
        String openClSource,
        String buildOptions,
        int maxInputBytes,
        int maxOutputBytes,
        int persistentStateBytes,
        int maxGlobalWorkItems,
        CpuKernelExecutor cpuFallback
    ) {
        /**
         * Validates all non-null, non-blank, and buffer/work-item bound invariants.
         *
         * @param ownerId              owning plugin or subsystem identifier
         * @param kernelId             unique kernel identifier within the owner namespace
         * @param kernelName           OpenCL C99 {@code __kernel} entry-point function name
         * @param openClSource         OpenCL C99 source code
         * @param buildOptions         optional OpenCL compiler build options string
         * @param maxInputBytes        maximum input buffer capacity in bytes ({@code >= 0})
         * @param maxOutputBytes       maximum output buffer capacity in bytes ({@code > 0})
         * @param persistentStateBytes persistent device state buffer size in bytes ({@code >= 0})
         * @param maxGlobalWorkItems   maximum 1D global work-item count per dispatch ({@code > 0})
         * @param cpuFallback          mandatory CPU fallback executor
         * @throws IllegalArgumentException if any string is blank or any size/bound is invalid
         */
        public KernelDescriptor {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(kernelId, "kernelId");
            Objects.requireNonNull(kernelName, "kernelName");
            Objects.requireNonNull(openClSource, "openClSource");
            Objects.requireNonNull(buildOptions, "buildOptions");
            Objects.requireNonNull(cpuFallback, "cpuFallback");
            if (ownerId.isBlank()) {
                throw new IllegalArgumentException("ownerId must not be blank");
            }
            if (kernelId.isBlank()) {
                throw new IllegalArgumentException("kernelId must not be blank");
            }
            if (kernelName.isBlank()) {
                throw new IllegalArgumentException("kernelName must not be blank");
            }
            if (openClSource.isBlank()) {
                throw new IllegalArgumentException("openClSource must not be blank");
            }
            if (maxInputBytes < 0) {
                throw new IllegalArgumentException("maxInputBytes must be >= 0");
            }
            if (maxOutputBytes <= 0) {
                throw new IllegalArgumentException("maxOutputBytes must be > 0");
            }
            if (persistentStateBytes < 0) {
                throw new IllegalArgumentException("persistentStateBytes must be >= 0");
            }
            if (maxGlobalWorkItems <= 0) {
                throw new IllegalArgumentException("maxGlobalWorkItems must be > 0");
            }
        }
    }

    /**
     * Single dispatch invocation request against a {@link ComputeKernelHandle}.
     *
     * @param input                     caller-supplied input buffer
     * @param inputBytes                number of readable bytes in {@code input}
     * @param output                    caller-supplied output buffer
     * @param outputBytes               number of writable bytes in {@code output}
     * @param params                    scalar integer parameters (defensively copied)
     * @param globalWorkItems           1D global work-item count (must be {@code > 0})
     * @param localWorkItems            1D local work-group size, or {@code 0} for runtime selection
     * @param clearOutputBeforeDispatch whether to zero-fill the output buffer before execution
     */
    record DispatchRequest(
        ByteBuffer input,
        int inputBytes,
        ByteBuffer output,
        int outputBytes,
        int[] params,
        int globalWorkItems,
        int localWorkItems,
        boolean clearOutputBeforeDispatch
    ) {
        /**
         * Validates buffer bounds and work-item counts, and defensively copies {@code params}.
         *
         * @param input                     caller-supplied input buffer
         * @param inputBytes                number of readable bytes in {@code input}
         * @param output                    caller-supplied output buffer
         * @param outputBytes               number of writable bytes in {@code output}
         * @param params                    scalar integer parameters (defensively copied)
         * @param globalWorkItems           1D global work-item count ({@code > 0})
         * @param localWorkItems            1D local work-group size, or {@code 0} for runtime selection
         * @param clearOutputBeforeDispatch whether to zero-fill the output buffer before execution
         * @throws IllegalArgumentException if buffer byte counts or work-item counts are out of bounds
         */
        public DispatchRequest {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(params, "params");
            if (inputBytes < 0 || inputBytes > input.capacity()) {
                throw new IllegalArgumentException("inputBytes out of bounds: " + inputBytes);
            }
            if (outputBytes <= 0 || outputBytes > output.capacity()) {
                throw new IllegalArgumentException("outputBytes out of bounds: " + outputBytes);
            }
            if (globalWorkItems <= 0) {
                throw new IllegalArgumentException("globalWorkItems must be > 0");
            }
            if (localWorkItems < 0) {
                throw new IllegalArgumentException("localWorkItems must be >= 0");
            }
            params = params.clone();
        }

        /**
         * Creates a dispatch request with automatic local work-group sizing and without pre-clearing output.
         *
         * @param input           caller-supplied input buffer
         * @param inputBytes      number of readable bytes in {@code input}
         * @param output          caller-supplied output buffer
         * @param outputBytes     number of writable bytes in {@code output}
         * @param params          scalar integer parameters
         * @param globalWorkItems 1D global work-item count
         * @return a validated {@link DispatchRequest}
         */
        public static DispatchRequest of(
            final ByteBuffer input,
            final int inputBytes,
            final ByteBuffer output,
            final int outputBytes,
            final int[] params,
            final int globalWorkItems
        ) {
            return new DispatchRequest(input, inputBytes, output, outputBytes, params, globalWorkItems, 0, false);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DispatchRequest other)) {
                return false;
            }
            return this.inputBytes == other.inputBytes
                && this.outputBytes == other.outputBytes
                && this.globalWorkItems == other.globalWorkItems
                && this.localWorkItems == other.localWorkItems
                && this.clearOutputBeforeDispatch == other.clearOutputBeforeDispatch
                && Objects.equals(this.input, other.input)
                && Objects.equals(this.output, other.output)
                && Arrays.equals(this.params, other.params);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                this.input,
                this.inputBytes,
                this.output,
                this.outputBytes,
                this.globalWorkItems,
                this.localWorkItems,
                this.clearOutputBeforeDispatch
            );
            return 31 * result + Arrays.hashCode(this.params);
        }

        @Override
        public String toString() {
            return "DispatchRequest[input=" + this.input
                + ", inputBytes=" + this.inputBytes
                + ", output=" + this.output
                + ", outputBytes=" + this.outputBytes
                + ", params=" + Arrays.toString(this.params)
                + ", globalWorkItems=" + this.globalWorkItems
                + ", localWorkItems=" + this.localWorkItems
                + ", clearOutputBeforeDispatch=" + this.clearOutputBeforeDispatch + "]";
        }
    }

    /**
     * Timing and execution metadata returned when a dispatch completes.
     *
     * @param backend       execution backend that processed the dispatch
     * @param computeNanos  kernel compute duration in nanoseconds
     * @param readbackNanos device-to-host readback duration in nanoseconds
     * @param outputBytes   number of bytes written to the output buffer
     * @param frameSequence monotonically increasing frame sequence number
     */
    record DispatchResult(
        ExecutionBackend backend,
        long computeNanos,
        long readbackNanos,
        int outputBytes,
        long frameSequence
    ) {
    }

    /**
     * Latest-frame-wins {@code 1..20 Hz} frame-paced compute session backed by a bounded 2-slot
     * mailbox. Submitting a new frame while a previous frame is still queued drops the stale
     * unstarted frame rather than growing an unbounded queue.
     */
    interface PacedSession extends AutoCloseable {

        /**
         * Returns the identifier of the kernel backing this session.
         *
         * @return kernel identifier
         */
        String kernelId();

        /**
         * Returns the requested target cadence in Hz.
         *
         * @return target cadence in {@code [MIN_TARGET_HZ, MAX_TARGET_HZ]}
         */
        int targetHz();

        /**
         * Returns the effective cadence after any automatic GPU-budget step-down ({@code 1..targetHz}).
         *
         * @return effective execution cadence in Hz
         */
        int effectiveHz();

        /**
         * Returns the total number of frames submitted to this session.
         *
         * @return submitted frame count
         */
        long submittedFrames();

        /**
         * Returns the total number of frames completed by this session.
         *
         * @return completed frame count
         */
        long completedFrames();

        /**
         * Returns the number of queued frames overwritten before execution started.
         *
         * @return dropped frame count
         */
        long droppedFrames();

        /**
         * Returns whether this paced session has been closed.
         *
         * @return {@code true} if closed
         */
        boolean isClosed();

        /**
         * Submits the latest frame request into the bounded mailbox. Never blocks the calling
         * thread ({@code S-005}).
         *
         * @param request frame dispatch request to enqueue
         * @return {@code true} if accepted, {@code false} if the session is closed
         */
        boolean submitLatest(DispatchRequest request);

        @Override
        void close();
    }

    /**
     * Scoped handle for a registered or opened compute kernel. Must be closed on plugin disable to
     * release native {@code cl_mem}/{@code cl_kernel}/{@code cl_program} resources and VRAM quota
     * ({@code S-002}).
     */
    interface ComputeKernelHandle extends AutoCloseable {

        /**
         * Returns the owning plugin or subsystem identifier.
         *
         * @return owner identifier
         */
        String ownerId();

        /**
         * Returns the identifier of this kernel.
         *
         * @return kernel identifier
         */
        String kernelId();

        /**
         * Returns whether this kernel is compiled and ready for OpenCL GPU execution.
         *
         * @return {@code true} if compiled for GPU execution
         */
        boolean isGpuCompiled();

        /**
         * Returns whether this kernel handle has been closed.
         *
         * @return {@code true} if closed
         */
        boolean isClosed();

        /**
         * Returns the maximum allowed input buffer size in bytes.
         *
         * @return maximum input bytes
         */
        int maxInputBytes();

        /**
         * Returns the maximum allowed output buffer size in bytes.
         *
         * @return maximum output bytes
         */
        int maxOutputBytes();

        /**
         * Returns the persistent state buffer size in bytes.
         *
         * @return persistent state bytes
         */
        int persistentStateBytes();

        /**
         * Dispatches a single off-tick compute batch asynchronously ({@code S-005}).
         *
         * @param request dispatch parameters and buffers
         * @return future completing with execution timing and metadata
         */
        CompletableFuture<DispatchResult> dispatchAsync(DispatchRequest request);

        /**
         * Starts a {@code 1..20 Hz} latest-frame-wins paced session on this kernel handle.
         *
         * @param targetHz   desired cadence in {@code [1, 20]}
         * @param onComplete off-tick callback invoked when each completed frame finishes
         * @return the started {@link PacedSession}
         */
        PacedSession startPacedSession(int targetHz, Consumer<DispatchResult> onComplete);

        @Override
        void close();
    }

    /**
     * Returns whether the compute service is enabled in server configuration.
     *
     * @return {@code true} if the compute service is enabled in server configuration
     */
    boolean isEnabled();

    /**
     * Returns whether at least one non-PoCL OpenCL GPU device is ready for compute dispatch.
     *
     * @return {@code true} if at least one non-PoCL OpenCL GPU device is ready for compute dispatch
     */
    boolean isGpuAvailable();

    /**
     * Returns the immutable set of built-in audited kernel identifiers.
     *
     * @return immutable set of built-in audited kernel identifiers
     */
    Set<String> builtInKernelIds();

    /**
     * Returns the total persistent and scratch VRAM currently reserved across all active handles.
     *
     * @return total persistent + scratch VRAM currently reserved across all active handles
     */
    long allocatedVramBytes();

    /**
     * Opens a handle to a built-in audited kernel (e.g.
     * {@link BuiltInKernels#MASS_EXPLOSION_RAYCAST}).
     *
     * @param ownerId         owning plugin or subsystem identifier (used for VRAM quota &amp; teardown)
     * @param builtInKernelId identifier from {@link BuiltInKernels}
     * @return scoped handle to the opened built-in kernel
     * @throws IllegalStateException    if the compute service is disabled
     * @throws IllegalArgumentException if {@code builtInKernelId} is unknown
     */
    ComputeKernelHandle openBuiltInKernel(String ownerId, String builtInKernelId);

    /**
     * Registers a custom OpenCL C99 kernel with a mandatory CPU fallback.
     *
     * <p>Fails closed with {@link SecurityException} unless {@code compute.allow-custom-opencl} is
     * enabled and {@code descriptor.ownerId()} is present in
     * {@code compute.custom-kernel-allowlist}.</p>
     *
     * @param descriptor immutable kernel registration descriptor
     * @return scoped handle to the registered custom kernel
     * @throws IllegalStateException if the compute service is disabled or VRAM quota is exceeded
     * @throws SecurityException     if custom OpenCL kernels are disallowed for {@code ownerId}
     */
    ComputeKernelHandle registerCustomKernel(KernelDescriptor descriptor);

    /**
     * Releases all active handles and paced sessions owned by {@code ownerId} (e.g., on plugin
     * disable, {@code S-002}).
     *
     * @param ownerId owning plugin or subsystem identifier
     * @return number of handles closed
     */
    int closeAllForOwner(String ownerId);

    /**
     * Global service holder populated by the server bootstrap at startup.
     */
    final class Holder {
        private static volatile @Nullable ComputeService instance;

        private Holder() {
        }
    }

    /**
     * Returns the registered global {@link ComputeService} instance, if available.
     *
     * @return the active {@link ComputeService}, or {@code null} if not registered
     */
    static @Nullable ComputeService get() {
        return Holder.instance;
    }

    /**
     * Registers or clears the global {@link ComputeService} instance.
     *
     * @param service service implementation to register, or {@code null} to clear
     */
    static void register(final @Nullable ComputeService service) {
        Holder.instance = service;
    }
}
