package ai.devflow.orchestrator;

import ai.devflow.agent.Agent;
import ai.devflow.agent.AgentResult;
import ai.devflow.agent.Finding;
import ai.devflow.event.RunEvent;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.tools.BuildTools;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sequences the crew and owns the only control flow in the system.
 *
 * <p>Central orchestration: the agents are pure {@code RunState -> AgentResult}
 * functions that never call each other. This class decides what runs next, and
 * holds only {@code AgentResult} records — never file contents or diffs.
 */
public class Orchestrator {

    public record RunOutcome(boolean approved, String reason, RunState state) {}

    private final Agent coder;
    private final Agent reviewer;
    private final RunEventPublisher events;
    private final int maxReviewIterations;
    private final int maxHumanIterations;
    private final Duration buildTimeout;

    public Orchestrator(Agent coder, Agent reviewer, RunEventPublisher events,
                        int maxReviewIterations, int maxHumanIterations, Duration buildTimeout) {
        this.coder = coder;
        this.reviewer = reviewer;
        this.events = events;
        this.maxReviewIterations = maxReviewIterations;
        this.maxHumanIterations = maxHumanIterations;
        this.buildTimeout = buildTimeout;
    }

    public RunOutcome run(RunState state, ApprovalGate gate) {
        String runId = state.runId();
        try {
            return execute(state, gate);
        } catch (RuntimeException e) {
            // I2: a model or transport failure (a 429, a dropped connection)
            // must become a FAILED outcome, never an escaped throwable that
            // skips the cleanup in the finally block below.
            state.setPhase(RunPhase.FAILED);
            String reason = "Run failed: " + e;
            events.publish(runId, RunEvent.of("error", reason));
            return new RunOutcome(false, reason, state);
        } finally {
            cleanUp(state);
            events.complete(runId);
        }
    }

    private RunOutcome execute(RunState state, ApprovalGate gate) {
        String runId = state.runId();

        emit(state, "step", "Workspace ready — branch " + state.workspace().branchName(),
                Map.of("branch", state.workspace().branchName()));

        // ---- Gate 1: pre-flight -------------------------------------------
        // Not a filesystem guard (PathGuard is, and the workspace is a
        // disposable temp copy) — this confirms "spend tokens on this repo,
        // for this task" before the first Opus 5 call.
        emit(state, "gate", "About to run CODER then REVIEWER on this task", Map.of(
                "gate", Gate.PRE_FLIGHT.name(),
                "branch", state.workspace().branchName(),
                "task", state.task()));
        ApprovalDecision preFlight = gate.await(Gate.PRE_FLIGHT);
        if (!preFlight.approved()) {
            if (preFlight.hasReason()) {
                // A reason at pre-flight refines the task rather than aborting.
                state.addFindings(List.of(Finding.fromHuman(preFlight.reason())));
                state.incrementHumanIterations();
                emit(state, "step", "Operator refined the task before starting", Map.of());
            } else {
                return aborted(state, "Rejected at pre-flight");
            }
        }

        AgentResult lastReview = null;

        while (state.reviewIterations() < maxReviewIterations) {
            state.incrementReviewIterations();

            // ---- Coder ----------------------------------------------------
            state.setPhase(RunPhase.CODING);
            emit(state, "step", "Coder — working…", Map.of("iteration", state.reviewIterations()));
            AgentResult coded = coder.run(state);
            state.record(coded);
            state.clearFindings();
            emit(state, "step", "Coder — " + coded.summary(),
                    Map.of("filesTouched", coded.filesTouched()));

            if (coded.status() == AgentResult.Status.FAILED) {
                return failed(state, "Coder failed: " + coded.summary());
            }

            // ---- Reviewer -------------------------------------------------
            state.setPhase(RunPhase.REVIEWING);
            emit(state, "step", "Reviewer — reading changed files…", Map.of());
            AgentResult reviewed = reviewer.run(state);
            state.record(reviewed);
            lastReview = reviewed;

            switch (reviewed.status()) {
                case FAILED -> {
                    // Fail closed — an unparseable review is not an approval.
                    return failed(state, "Review failed: " + reviewed.summary());
                }
                case NEEDS_WORK -> {
                    state.addFindings(reviewed.findings());
                    emit(state, "step", "Reviewer — needs work: " + reviewed.summary(),
                            Map.of("findings", reviewed.findings().size()));
                    continue;
                }
                case OK -> {
                    emit(state, "step", "Reviewer — approved: " + reviewed.summary(), Map.of());
                }
            }

            // ---- Gate 2: before the build ---------------------------------
            // Arbitrary code execution: this runs the TARGET repo's wrapper.
            // Also the first gate where a real file list exists.
            List<String> changed = state.gitTools().changedFiles();
            emit(state, "gate", "About to run the build on " + changed.size() + " changed file(s)",
                    Map.of("gate", Gate.BEFORE_BUILD.name(), "filesTouched", changed));
            ApprovalDecision beforeBuild = gate.await(Gate.BEFORE_BUILD);
            if (!beforeBuild.approved()) {
                if (beforeBuild.hasReason() && state.humanIterations() < maxHumanIterations) {
                    state.addFindings(List.of(Finding.fromHuman(beforeBuild.reason())));
                    state.incrementHumanIterations();
                    // A human correction does not consume the reviewer's cap.
                    decrementReviewIteration(state);
                    emit(state, "step", "Operator sent it back: " + beforeBuild.reason(), Map.of());
                    continue;
                }
                return aborted(state, "Rejected before the build");
            }

            // ---- Build ----------------------------------------------------
            state.setPhase(RunPhase.BUILDING);
            emit(state, "step", "Running the target repository's build…", Map.of());
            var build = new BuildTools(state.workspace(), buildTimeout).build("test");
            emit(state, "step", build.success() ? "Build passed" : "Build failed",
                    Map.of("success", build.success()));

            // ---- Gate 3: before the commit --------------------------------
            // C1-b: a run that changed nothing must never be reported approved.
            // Nothing was disguised here (filesTouched is visibly empty), but
            // approved=true is an operator-facing claim, so refuse it.
            if (changed.isEmpty()) {
                return failed(state, "Refusing to approve: the coder made no changes");
            }

            emit(state, "gate", "About to commit " + changed.size() + " file(s)", Map.of(
                    "gate", Gate.BEFORE_COMMIT.name(),
                    "filesTouched", changed,
                    "buildPassed", build.success()));
            ApprovalDecision beforeCommit = gate.await(Gate.BEFORE_COMMIT);
            if (!beforeCommit.approved()) {
                if (beforeCommit.hasReason() && state.humanIterations() < maxHumanIterations) {
                    state.addFindings(List.of(Finding.fromHuman(beforeCommit.reason())));
                    state.incrementHumanIterations();
                    decrementReviewIteration(state);
                    emit(state, "step", "Operator sent it back: " + beforeCommit.reason(), Map.of());
                    continue;
                }
                return aborted(state, "Rejected before the commit");
            }

            // ---- Commit ---------------------------------------------------
            state.setPhase(RunPhase.COMMITTING);
            String committed = state.gitTools().commit("devflowai: " + state.task());
            emit(state, "step", committed, Map.of());

            state.setPhase(RunPhase.DONE);
            emit(state, "done", "Approved and committed on " + state.workspace().branchName(),
                    Map.of("branch", state.workspace().branchName(),
                           "inputTokens", state.totalTokens().input(),
                           "outputTokens", state.totalTokens().output()));
            return new RunOutcome(true, "Approved by reviewer and operator", state);
        }

        String reason = lastReview == null
                ? "Review loop ended without a review"
                : "Review loop hit the cap of " + maxReviewIterations + " iterations";
        return failed(state, reason);
    }

    /**
     * Gives back one reviewer iteration after a human correction. The reviewer
     * cap exists to stop two models ping-ponging at the operator's expense; a
     * human deliberately steering is charged to {@code humanIterations}
     * instead (spec §5.2).
     */
    private void decrementReviewIteration(RunState state) {
        if (state.reviewIterations() > 0) state.rollBackReviewIteration();
    }

    private RunOutcome aborted(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "aborted", reason, Map.of());
        return new RunOutcome(false, reason, state);
    }

    private RunOutcome failed(RunState state, String reason) {
        state.setPhase(RunPhase.FAILED);
        emit(state, "error", reason, Map.of());
        return new RunOutcome(false, reason, state);
    }

    private void emit(RunState state, String type, String message, Map<String, Object> data) {
        events.publish(state.runId(), RunEvent.of(type, message, data));
    }

    /** Runs on every exit path — normal, rejected, or thrown. */
    private void cleanUp(RunState state) {
        try {
            state.workspace().cleanup();
        } catch (Exception e) {
            events.publish(state.runId(),
                    RunEvent.of("warn", "Workspace cleanup failed: " + e.getMessage()));
        }
    }

    public int maxHumanIterations() { return maxHumanIterations; }
}
