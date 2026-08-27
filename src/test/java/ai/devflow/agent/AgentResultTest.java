package ai.devflow.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AgentResultTest {

    @Test
    void okResultCarriesNoFindings() {
        var r = AgentResult.ok("coder", "did the thing", List.of("A.java"), TokenUsage.NONE);
        assertThat(r.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void humanFindingHasNoFileOrLine() {
        var f = Finding.fromHuman("use a DTO, don't annotate the entity");
        assertThat(f.origin()).isEqualTo(Finding.Origin.HUMAN);
        assertThat(f.file()).isNull();
        assertThat(f.line()).isNull();
        assertThat(f.severity()).isEqualTo(Finding.Severity.HIGH);
    }

    @Test
    void tokenUsageAccumulates() {
        assertThat(new TokenUsage(10, 5).plus(new TokenUsage(1, 2)))
                .isEqualTo(new TokenUsage(11, 7));
    }
}
