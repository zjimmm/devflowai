package ai.devflow.memory;

/** A flat, whole-file list of durable repo facts, keyed by repo (spec §6). */
public interface MemoryStore {

    /** Whole-file content, or "" if the repo has no memory yet. */
    String read(String repoSlug);

    /** Appends a fact as a new bullet line, skipping an exact duplicate. Returns the file's content after the append. */
    String append(String repoSlug, String fact);
}
