package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.PullRequestResult;
import ai.devflow.tools.WorkflowDispatchResult;
import ai.devflow.workspace.Workspace;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubActionsStagingDispatcherTest {

    @Test
    void blankWorkflowDisablesDispatch() {
        var dispatcher = new GitHubActionsStagingDispatcher(new RecordingClient(), " ", "main");

        assertThat(dispatcher.isConfigured()).isFalse();
        assertThatThrownBy(() -> dispatcher.dispatch("https://github.com/o/r"))
                .hasMessageContaining("not configured");
    }

    @Test
    void configuredDispatcherUsesTheConfiguredWorkflowAndRef() throws Exception {
        var client = new RecordingClient();
        var dispatcher = new GitHubActionsStagingDispatcher(client, "staging.yml", "integration");

        var result = dispatcher.dispatch("https://github.com/o/r");

        assertThat(dispatcher.isConfigured()).isTrue();
        assertThat(client.workflow).isEqualTo("staging.yml");
        assertThat(client.ref).isEqualTo("integration");
        assertThat(result.htmlUrl()).isEqualTo("https://github.com/o/r/actions/runs/7");
    }

    private static class RecordingClient implements GitHubClient {
        String workflow;
        String ref;

        @Override public void push(Workspace workspace, String branchName) { }
        @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
            throw new UnsupportedOperationException();
        }
        @Override public WorkflowDispatchResult dispatchWorkflow(String repoUrl, String workflow, String ref) {
            this.workflow = workflow;
            this.ref = ref;
            return new WorkflowDispatchResult(7L, "https://github.com/o/r/actions/runs/7");
        }
    }
}
