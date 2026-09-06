package ai.devflow.tools;

/** Checked -- a push or PR failure is always a real, operator-visible condition, never swallowed. */
public class GitHubClientException extends Exception {
    public GitHubClientException(String message) {
        super(message);
    }

    public GitHubClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
