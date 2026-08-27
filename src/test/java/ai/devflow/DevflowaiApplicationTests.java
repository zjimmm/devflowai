package ai.devflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.anthropic.api-key=test-key-not-used")
class DevflowaiApplicationTests {
    @Test
    void contextLoads() {}
}
