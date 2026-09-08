package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.WorkflowRun;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class PollingReleaseObserver implements ReleaseObserver {

    private static final Set<String> SUCCESS_CONCLUSIONS = Set.of("success", "neutral", "skipped");

    private final GitHubClient gitHubClient;
    private final Duration pollInterval;
    private final Duration timeout;

    public PollingReleaseObserver(GitHubClient gitHubClient, Duration pollInterval, Duration timeout) {
        this.gitHubClient = Objects.requireNonNull(gitHubClient, "gitHubClient");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.timeout = requirePositive(timeout, "timeout");
    }

    @Override
    public ReleaseObservation observe(String repoUrl, long workflowRunId, Consumer<ReleaseObservation> onUpdate)
            throws GitHubClientException {
        long deadline = System.nanoTime() + timeout.toNanos();
        ReleaseObservation previous = null;
        while (true) {
            WorkflowRun workflowRun = gitHubClient.getWorkflowRun(repoUrl, workflowRunId);
            ReleaseObservation observation = new ReleaseObservation(classify(workflowRun), workflowRun);
            if (!observation.equals(previous)) {
                onUpdate.accept(observation);
                previous = observation;
            }
            if (observation.status() != VerificationStatus.PENDING) return observation;
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                ReleaseObservation timedOut = new ReleaseObservation(VerificationStatus.TIMED_OUT, workflowRun);
                onUpdate.accept(timedOut);
                return timedOut;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(), remainingNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitHubClientException("Release verification interrupted", e);
            }
        }
    }

    static VerificationStatus classify(WorkflowRun workflowRun) {
        if (!"completed".equals(workflowRun.status())) return VerificationStatus.PENDING;
        return SUCCESS_CONCLUSIONS.contains(workflowRun.conclusion())
                ? VerificationStatus.PASSED
                : VerificationStatus.FAILED;
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
