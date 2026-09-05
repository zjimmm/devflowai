package ai.devflow.memory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileMemoryStoreTest {

    @TempDir Path root;
    MemoryStore store;

    @BeforeEach
    void setUp() { store = new FileMemoryStore(root); }

    @Test
    void readIsEmptyForARepoWithNoMemoryYet() {
        assertThat(store.read("fixture")).isEmpty();
    }

    @Test
    void appendThenReadFindsTheFact() {
        store.append("fixture", "tests use JUnit 5 + AssertJ");

        assertThat(store.read("fixture")).contains("tests use JUnit 5 + AssertJ");
    }

    @Test
    void appendingTheSameFactTwiceDoesNotDuplicateIt() {
        store.append("fixture", "tests use JUnit 5 + AssertJ");
        store.append("fixture", "tests use JUnit 5 + AssertJ");

        String content = store.read("fixture");
        int occurrences = content.split("tests use JUnit 5", -1).length - 1;
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void appendingADifferentFactKeepsBoth() {
        store.append("fixture", "tests use JUnit 5 + AssertJ");
        store.append("fixture", "the build tool is Gradle");

        String content = store.read("fixture");
        assertThat(content).contains("tests use JUnit 5 + AssertJ");
        assertThat(content).contains("the build tool is Gradle");
    }

    @Test
    void differentReposAreIsolated() {
        store.append("repo-a", "fact a");
        store.append("repo-b", "fact b");

        assertThat(store.read("repo-a")).contains("fact a").doesNotContain("fact b");
        assertThat(store.read("repo-b")).contains("fact b").doesNotContain("fact a");
    }
}
