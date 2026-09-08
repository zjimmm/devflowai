package ai.devflow.orchestrator;

import ai.devflow.tools.CiCheck;
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.PullRequestResult;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollingCiObserverTest {

    @Test
    void classifyDistinguishesPendingPassingAndFailingChecks() {
        assertThat(PollingCiObserver.classify(List.of())).isEqualTo(CiStatus.PENDING);
        assertThat(PollingCiObserver.classify(List.of(
                new CiCheck("Build", "in_progress", null, null)))).isEqualTo(CiStatus.PENDING);
        assertThat(PollingCiObserver.classify(List.of(
                new CiCheck("Build", "completed", "success", null),
                new CiCheck("Lint", "completed", "skipped", null)))).isEqualTo(CiStatus.PASSED);
        assertThat(PollingCiObserver.classify(List.of(
                new CiCheck("Build", "completed", "failure", null)))).isEqualTo(CiStatus.FAILED);
    }

    @Test
    void observeReturnsImmediatelyWhenChecksPass() throws Exception {
        var observer = new PollingCiObserver(clientReturning(List.of(
                new CiCheck("Build", "completed", "success", "https://github.com/o/r/checks/1"))),
                Duration.ofSeconds(1), Duration.ofMinutes(1));
        var updates = new ArrayList<CiObservation>();

        var result = observer.observe("https://github.com/o/r", "branch", updates::add);

        assertThat(result.status()).isEqualTo(CiStatus.PASSED);
        assertThat(updates).containsExactly(result);
    }

    @Test
    void observeTimesOutWhenNoChecksAppear() throws Exception {
        var observer = new PollingCiObserver(clientReturning(List.of()), Duration.ofSeconds(1), Duration.ofNanos(1));
        var updates = new ArrayList<CiObservation>();

        var result = observer.observe("https://github.com/o/r", "branch", updates::add);

        assertThat(result.status()).isEqualTo(CiStatus.TIMED_OUT);
        assertThat(updates).extracting(CiObservation::status)
                .containsExactly(CiStatus.PENDING, CiStatus.TIMED_OUT);
    }

    @Test
    void invalidPollingDurationsAreRejected() {
        assertThatThrownBy(() -> new PollingCiObserver(clientReturning(List.of()), Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollInterval");
    }

    private GitHubClient clientReturning(List<CiCheck> checks) {
        return new GitHubClient() {
            @Override public void push(Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException();
            }
            @Override public List<CiCheck> listCiChecks(String repoUrl, String ref) {
                return checks;
            }
        };
    }
}
