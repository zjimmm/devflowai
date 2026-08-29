package ai.devflow.orchestrator;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parks the orchestrator thread until a human decides, or the timeout expires.
 *
 * <p>SSE is server-to-client only and cannot carry the answer back, so the
 * decision arrives on a different thread via {@code POST /approve} calling
 * {@link #decide}. This class is the meeting point (spec §5.1).
 *
 * <p>A timeout is treated as a bare rejection: the run aborts and the workspace
 * is cleaned up. Silence is never consent.
 */
public class ApprovalGate {

    private final Duration timeout;
    private final AtomicReference<Pending> pendingRef = new AtomicReference<>();

    private record Pending(Gate gate, CompletableFuture<ApprovalDecision> future) {}

    public ApprovalGate(Duration timeout) {
        this.timeout = timeout;
    }

    /** The gate currently awaiting a decision, or null if the run is not paused. */
    public Gate pending() {
        Pending current = pendingRef.get();
        return current == null ? null : current.gate();
    }

    /**
     * Blocks the calling (orchestrator) thread until {@link #decide} is called
     * or the timeout expires.
     */
    public ApprovalDecision await(Gate gate) {
        Pending mine = new Pending(gate, new CompletableFuture<>());
        pendingRef.set(mine);
        try {
            return mine.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ApprovalDecision.reject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalDecision.reject();
        } catch (ExecutionException e) {
            return ApprovalDecision.reject();
        } finally {
            // Clear only our own registration. A plain pendingRef.set(null)
            // here would risk clobbering a *different* Pending if this call's
            // cleanup were ever delayed past the start of a subsequent
            // await() — compareAndSet makes the cleanup a no-op once
            // decide() (or a later await()) has already moved pendingRef on.
            pendingRef.compareAndSet(mine, null);
        }
    }

    /**
     * Supplies the decision the orchestrator is waiting for.
     *
     * @return false if nothing was pending — a stale or duplicate approval,
     *         which the controller reports rather than silently swallowing.
     */
    public boolean decide(ApprovalDecision decision) {
        Pending current = pendingRef.getAndSet(null);
        if (current == null) return false;
        return current.future().complete(decision);
    }
}
