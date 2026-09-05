package ai.devflow.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugTest {

    @Test
    void lowercasesAndTrims() {
        assertThat(Slug.of("  Add Validation  ")).isEqualTo("add-validation");
    }

    @Test
    void collapsesRunsOfNonAlphanumericCharactersIntoOneDash() {
        assertThat(Slug.of("Add   Validation!!!  To Controller")).isEqualTo("add-validation-to-controller");
    }

    @Test
    void stripsLeadingAndTrailingDashes() {
        assertThat(Slug.of("--@Valid--")).isEqualTo("valid");
    }

    @Test
    void slugifiesARealisticRepoUrl() {
        assertThat(Slug.of("https://github.com/zjimmm/devflowai.git"))
                .isEqualTo("https-github-com-zjimmm-devflowai-git");
    }
}
