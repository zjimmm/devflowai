package ai.devflow.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillFileFormatTest {

    @Test
    void rendersFrontmatterThenBody() {
        var draft = new SkillDraft("spring-controller-validation",
                "Adding bean validation to a Spring MVC controller",
                List.of("validation", "controller", "@Valid"),
                "## Steps\n1. Annotate the DTO fields\n");

        String rendered = SkillFileFormat.render(draft, "run-2026-08-27-a3f1");

        assertThat(rendered).startsWith("---\n");
        assertThat(rendered).contains("name: spring-controller-validation\n");
        assertThat(rendered).contains("description: Adding bean validation to a Spring MVC controller\n");
        assertThat(rendered).contains("triggers: [validation, controller, @Valid]\n");
        assertThat(rendered).contains("learned_from: run-2026-08-27-a3f1\n");
        assertThat(rendered).contains("## Steps\n1. Annotate the DTO fields\n");
    }

    @Test
    void roundTripsThroughParseIndexEntry() {
        var draft = new SkillDraft("spring-controller-validation",
                "Adding bean validation to a Spring MVC controller",
                List.of("validation", "controller", "@Valid"),
                "## Steps\n1. Annotate the DTO fields\n");

        String rendered = SkillFileFormat.render(draft, "run-1");
        SkillIndexEntry entry = SkillFileFormat.parseIndexEntry(rendered);

        assertThat(entry).isNotNull();
        assertThat(entry.name()).isEqualTo("spring-controller-validation");
        assertThat(entry.description()).isEqualTo("Adding bean validation to a Spring MVC controller");
        assertThat(entry.triggers()).containsExactly("validation", "controller", "@Valid");
    }

    // The spec's own §6.1 example has a quoted item ("@Valid") next to an
    // unquoted multi-word one (request body) in the same trigger list — the
    // parser must handle both without a YAML library (see this plan's design
    // note on hand-rolling this instead of pulling in one).
    @Test
    void parsesTheSpecsOwnExampleVerbatim() {
        String content = """
            ---
            name: spring-controller-validation
            description: Adding bean validation to a Spring MVC controller
            triggers: [validation, controller, "@Valid", request body]
            learned_from: run-2026-08-27-a3f1
            ---

            ## Steps
            1. Annotate the DTO fields with `jakarta.validation` constraints
            """;

        SkillIndexEntry entry = SkillFileFormat.parseIndexEntry(content);

        assertThat(entry.name()).isEqualTo("spring-controller-validation");
        assertThat(entry.triggers()).containsExactly("validation", "controller", "@Valid", "request body");
    }

    @Test
    void returnsNullWhenThereIsNoFrontmatter() {
        assertThat(SkillFileFormat.parseIndexEntry("just a plain markdown file\nwith no frontmatter\n")).isNull();
    }
}
