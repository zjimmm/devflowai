package ai.devflow.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileMemoryStore implements MemoryStore {

    private final Path root;

    public FileMemoryStore(Path root) { this.root = root; }

    @Override
    public String read(String repoSlug) {
        Path file = root.resolve(repoSlug + ".md");
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String append(String repoSlug, String fact) {
        String existing = read(repoSlug);
        String line = "- " + fact.strip();
        if (existing.lines().anyMatch(l -> l.strip().equals(line))) {
            return existing;
        }
        String updated = existing.isBlank() ? line + "\n" : existing.stripTrailing() + "\n" + line + "\n";
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(repoSlug + ".md"), updated);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return updated;
    }
}
