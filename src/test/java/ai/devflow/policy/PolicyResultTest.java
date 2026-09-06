package ai.devflow.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyResultTest {

    @Test
    void okIsAlwaysPassed() {
        assertThat(PolicyResult.ok().passed()).isTrue();
    }

    @Test
    void aFailingResultRejectsANullReason() {
        assertThatThrownBy(() -> new PolicyResult(false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailingResultRejectsABlankReason() {
        assertThatThrownBy(() -> new PolicyResult(false, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPassingResultMayHaveANullReason() {
        assertThat(new PolicyResult(true, null).reason()).isNull();
    }
}
