package ai.devflow.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.ai.anthropic.api-key=test-key-not-used")
@AutoConfigureMockMvc
class StaticPageTest {

    @Autowired MockMvc mvc;

    @Test
    void servesTheOperatorPageAtRoot() throws Exception {
        // Spring Boot serves "/" by forwarding internally to index.html
        // (WelcomePageHandlerMapping). MockMvc's mock servlet container never
        // executes that forward -- MockRequestDispatcher.forward() only
        // records the forwarded URL for assertions, it doesn't re-dispatch --
        // so "/" always comes back 200 with an empty body under MockMvc, real
        // container or not. We assert routing at "/" succeeds, and check the
        // actual bytes at the concrete resource path the forward targets,
        // which a real server (verified by hand in Step 5) serves at "/" too.
        mvc.perform(get("/")).andExpect(status().isOk());

        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DevFlowAI")))
                // The three things the operator actually interacts with.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"task\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"run\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EventSource")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"history\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/runs/history")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"audit\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"audit-list\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"strategy\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"direct\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"open-pr\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"release\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"run-form\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"stage-rail\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"metric-delivery\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<th>CI</th>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<th>Release</th>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<th>Verify</th>")));

        mvc.perform(get("/assets/devflowai-guild-party.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
    }
}
