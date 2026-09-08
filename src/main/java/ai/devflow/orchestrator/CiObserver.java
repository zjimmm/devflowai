package ai.devflow.orchestrator;

import ai.devflow.tools.GitHubClientException;

import java.util.function.Consumer;

/** Waits for remote CI without coupling executors to a polling implementation. */
public interface CiObserver {
    CiObservation observe(String repoUrl, String ref, Consumer<CiObservation> onUpdate) throws GitHubClientException;
}
