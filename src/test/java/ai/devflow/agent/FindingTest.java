package ai.devflow.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FindingTest {

    @Test
    void fromPolicyProducesAPolicyOriginHighSeverityFinding() {
        Finding finding = Finding.fromPolicy("the build did not pass");

        assertThat(finding.origin()).isEqualTo(Finding.Origin.POLICY);
        assertThat(finding.severity()).isEqualTo(Finding.Severity.HIGH);
        assertThat(finding.file()).isNull();
        assertThat(finding.line()).isNull();
        assertThat(finding.message()).isEqualTo("the build did not pass");
    }
}
