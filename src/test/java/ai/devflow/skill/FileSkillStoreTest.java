package ai.devflow.skill;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSkillStoreTest {

    @TempDir Path root;
    SkillStore store;

    @BeforeEach
    void setUp() { store = new FileSkillStore(root); }

    @Test
    void indexIsEmptyForARepoWithNoSkillsYet() {
        assertThat(store.index("fixture")).isEmpty();
    }

    @Test
    void writeThenIndexFindsIt() {
        var draft = new SkillDraft("spring-controller-validation", "Adding bean validation",
                List.of("validation"), "## Steps\n1. Do it\n");

        store.write("fixture", "run-1", draft);
        List<SkillIndexEntry> index = store.index("fixture");

        assertThat(index).hasSize(1);
        assertThat(index.get(0).name()).isEqualTo("spring-controller-validation");
        assertThat(index.get(0).description()).isEqualTo("Adding bean validation");
    }

    @Test
    void readFullReturnsTheBody() {
        var draft = new SkillDraft("spring-controller-validation", "d", List.of(), "## Steps\n1. Do it\n");
        store.write("fixture", "run-1", draft);

        assertThat(store.readFull("fixture", "spring-controller-validation")).contains("## Steps\n1. Do it\n");
    }

    @Test
    void readFullReturnsEmptyForAnUnknownSkill() {
        assertThat(store.readFull("fixture", "does-not-exist")).isEmpty();
    }

    @Test
    void writingAgainWithTheSameNameOverwritesRatherThanDuplicating() {
        var first = new SkillDraft("spring-controller-validation", "first version", List.of(), "v1");
        var second = new SkillDraft("spring-controller-validation", "second version", List.of(), "v2");

        store.write("fixture", "run-1", first);
        store.write("fixture", "run-2", second);

        List<SkillIndexEntry> index = store.index("fixture");
        assertThat(index).hasSize(1);
        assertThat(index.get(0).description()).isEqualTo("second version");
        assertThat(store.readFull("fixture", "spring-controller-validation")).contains("v2");
    }

    @Test
    void differentReposAreIsolated() {
        store.write("repo-a", "run-1", new SkillDraft("s", "d", List.of(), "a"));
        store.write("repo-b", "run-1", new SkillDraft("s", "d", List.of(), "b"));

        assertThat(store.readFull("repo-a", "s")).contains("a");
        assertThat(store.readFull("repo-b", "s")).contains("b");
    }
}
