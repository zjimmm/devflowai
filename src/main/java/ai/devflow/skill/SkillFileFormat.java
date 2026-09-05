package ai.devflow.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link SkillDraft} to the on-disk markdown-with-frontmatter shape
 * (spec §6.1) and parses just the frontmatter back out for the cheap index
 * the picker reads (spec §6.2). Hand-rolled rather than a YAML library: the
 * frontmatter shape is narrow and fully controlled by {@link #render} — this
 * only ever has to parse what this same class wrote.
 */
public final class SkillFileFormat {

    private SkillFileFormat() {}

    public static String render(SkillDraft draft, String learnedFromRunId) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(draft.name()).append('\n');
        sb.append("description: ").append(draft.description()).append('\n');
        sb.append("triggers: [").append(String.join(", ", draft.triggers())).append("]\n");
        sb.append("learned_from: ").append(learnedFromRunId).append('\n');
        sb.append("---\n\n");
        sb.append(draft.body());
        return sb.toString();
    }

    /** Parses only the frontmatter block. Returns null if the file has none. */
    public static SkillIndexEntry parseIndexEntry(String fileContent) {
        String[] lines = fileContent.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) return null;

        String name = null;
        String description = null;
        List<String> triggers = List.of();

        int i = 1;
        for (; i < lines.length && !lines[i].strip().equals("---"); i++) {
            String line = lines[i];
            if (line.startsWith("name:")) {
                name = line.substring("name:".length()).strip();
            } else if (line.startsWith("description:")) {
                description = line.substring("description:".length()).strip();
            } else if (line.startsWith("triggers:")) {
                triggers = parseTriggers(line.substring("triggers:".length()).strip());
            }
        }
        if (i == lines.length) return null; // never found the closing '---'
        if (name == null || description == null) return null;
        return new SkillIndexEntry(name, description, triggers);
    }

    private static List<String> parseTriggers(String bracketed) {
        String inner = bracketed.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.isBlank()) return List.of();
        List<String> items = new ArrayList<>();
        for (String raw : inner.split(",")) {
            String item = raw.strip();
            if (item.length() >= 2 && item.startsWith("\"") && item.endsWith("\"")) {
                item = item.substring(1, item.length() - 1);
            }
            items.add(item);
        }
        return items;
    }
}
