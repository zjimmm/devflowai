package ai.devflow.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Host-side source of truth for skills, keyed by repo (spec §6.4): a run's
 * workspace is a temp directory that gets deleted on every exit path, so
 * skills written only there would evaporate with it. {@code root} is
 * injected so tests use a scratch directory instead of the real
 * {@code ~/.devflowai/skills}.
 */
public class FileSkillStore implements SkillStore {

    private final Path root;

    public FileSkillStore(Path root) { this.root = root; }

    @Override
    public synchronized List<SkillIndexEntry> index(String repoSlug) {
        Path dir = root.resolve(repoSlug);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<SkillIndexEntry> entries = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                SkillIndexEntry entry = SkillFileFormat.parseIndexEntry(Files.readString(file));
                if (entry != null) entries.add(entry);
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized String readFull(String repoSlug, String name) {
        Path file = root.resolve(repoSlug).resolve(SkillDraft.slugify(name) + ".md");
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized String write(String repoSlug, String runId, SkillDraft draft) {
        String rendered = SkillFileFormat.render(draft, runId);
        Path dir = root.resolve(repoSlug);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(draft.slug() + ".md"), rendered);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rendered;
    }
}
