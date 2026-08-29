package ai.devflow.orchestrator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest {

    @Test
    void awaitReturnsTheDecisionSuppliedByAnotherThread() throws Exception {
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalDecision> waiting = pool.submit(() -> gate.await(Gate.PRE_FLIGHT));

            // Wait for the gate to actually be parked before deciding.
            await(() -> gate.pending() == Gate.PRE_FLIGHT);
            assertThat(gate.decide(ApprovalDecision.approve())).isTrue();

            assertThat(waiting.get(5, TimeUnit.SECONDS).approved()).isTrue();
            assertThat(gate.pending()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectionWithAReasonCarriesTheReason() throws Exception {
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalDecision> waiting = pool.submit(() -> gate.await(Gate.BEFORE_BUILD));
            await(() -> gate.pending() == Gate.BEFORE_BUILD);
            gate.decide(ApprovalDecision.rejectWith("use a DTO, don't annotate the entity"));

            ApprovalDecision decision = waiting.get(5, TimeUnit.SECONDS);
            assertThat(decision.approved()).isFalse();
            assertThat(decision.hasReason()).isTrue();
            assertThat(decision.reason()).contains("use a DTO");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timingOutCountsAsRejectionWithoutAReason() {
        var gate = new ApprovalGate(Duration.ofMillis(150));
        ApprovalDecision decision = gate.await(Gate.BEFORE_COMMIT);
        assertThat(decision.approved()).isFalse();
        assertThat(decision.hasReason()).isFalse();
    }

    @Test
    void decidingWhenNothingIsPendingReturnsFalse() {
        var gate = new ApprovalGate(Duration.ofSeconds(5));
        assertThat(gate.pending()).isNull();
        assertThat(gate.decide(ApprovalDecision.approve())).isFalse();
    }

    @Test
    void bareRejectionHasNoReason() {
        assertThat(ApprovalDecision.reject().hasReason()).isFalse();
        assertThat(ApprovalDecision.rejectWith("   ").hasReason())
                .as("blank reason is not a reason")
                .isFalse();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition never became true");
            Thread.sleep(5);
        }
    }
}
