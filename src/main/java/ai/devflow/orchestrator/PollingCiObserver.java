package ai.devflow.orchestrator;

import ai.devflow.tools.CiCheck;
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Polls GitHub checks until they settle or the configured observation window ends. */
public class PollingCiObserver implements CiObserver {

    private static final Set<String> SUCCESS_CONCLUSIONS = Set.of("success", "neutral", "skipped");

    private final GitHubClient gitHubClient;
    private final Duration pollInterval;
    private final Duration timeout;

    public PollingCiObserver(GitHubClient gitHubClient, Duration pollInterval, Duration timeout) {
        this.gitHubClient = Objects.requireNonNull(gitHubClient, "gitHubClient");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.timeout = requirePositive(timeout, "timeout");
    }

    @Override
    public CiObservation observe(String repoUrl, String ref, Consumer<CiObservation> onUpdate)
            throws GitHubClientException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<CiCheck> checks = gitHubClient.listCiChecks(repoUrl, ref);
            CiObservation observation = new CiObservation(classify(checks), checks);
            onUpdate.accept(observation);
            if (observation.status() != CiStatus.PENDING) return observation;
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                CiObservation timedOut = new CiObservation(CiStatus.TIMED_OUT, checks);
                onUpdate.accept(timedOut);
                return timedOut;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(), remainingNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitHubClientException("CI observation interrupted", e);
            }
        }
    }

    static CiStatus classify(List<CiCheck> checks) {
        if (checks.isEmpty() || checks.stream().anyMatch(check -> !"completed".equals(check.status()))) {
            return CiStatus.PENDING;
        }
        return checks.stream().allMatch(check -> SUCCESS_CONCLUSIONS.contains(check.conclusion()))
                ? CiStatus.PASSED
                : CiStatus.FAILED;
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
