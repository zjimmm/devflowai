package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.PullRequestResult;
import ai.devflow.tools.WorkflowRun;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollingReleaseObserverTest {

    @Test
    void classifyDistinguishesPendingPassingAndFailingRuns() {
        assertThat(PollingReleaseObserver.classify(new WorkflowRun("in_progress", null, null)))
                .isEqualTo(VerificationStatus.PENDING);
        assertThat(PollingReleaseObserver.classify(new WorkflowRun("completed", "success", null)))
                .isEqualTo(VerificationStatus.PASSED);
        assertThat(PollingReleaseObserver.classify(new WorkflowRun("completed", "failure", null)))
                .isEqualTo(VerificationStatus.FAILED);
    }

    @Test
    void observeReturnsTerminalWorkflowResult() throws Exception {
        var observer = new PollingReleaseObserver(clientReturning(new WorkflowRun("completed", "success",
                "https://github.com/o/r/actions/runs/7")), Duration.ofSeconds(1), Duration.ofMinutes(1));
        var updates = new ArrayList<ReleaseObservation>();

        var result = observer.observe("https://github.com/o/r", 7, updates::add);

        assertThat(result.status()).isEqualTo(VerificationStatus.PASSED);
        assertThat(updates).containsExactly(result);
    }

    @Test
    void observeTimesOutWithoutRepeatingAnUnchangedPendingEvent() throws Exception {
        var observer = new PollingReleaseObserver(clientReturning(new WorkflowRun("queued", null, null)),
                Duration.ofSeconds(1), Duration.ofNanos(1));
        var updates = new ArrayList<ReleaseObservation>();

        var result = observer.observe("https://github.com/o/r", 7, updates::add);

        assertThat(result.status()).isEqualTo(VerificationStatus.TIMED_OUT);
        assertThat(updates).extracting(ReleaseObservation::status)
                .containsExactly(VerificationStatus.PENDING, VerificationStatus.TIMED_OUT);
    }

    @Test
    void invalidPollingDurationIsRejected() {
        assertThatThrownBy(() -> new PollingReleaseObserver(clientReturning(new WorkflowRun("queued", null, null)),
                Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollInterval");
    }

    private GitHubClient clientReturning(WorkflowRun workflowRun) {
        return new GitHubClient() {
            @Override public void push(Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException();
            }
            @Override public WorkflowRun getWorkflowRun(String repoUrl, long workflowRunId) {
                return workflowRun;
            }
        };
    }
}
