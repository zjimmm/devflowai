package ai.devflow.orchestrator;

import ai.devflow.agent.*;
import ai.devflow.event.RunEventPublisher;
import ai.devflow.memory.MemoryStore;
import ai.devflow.policy.*;
import ai.devflow.skill.*;
import ai.devflow.tools.GitHubClient;
import ai.devflow.tools.GitHubClientException;
import ai.devflow.tools.PullRequestResult;
import ai.devflow.workspace.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorTest {

    Workspace workspace;
    RunEventPublisher events;
    ExecutorService pool;
    Agent planner = defaultPlanner();
    PolicyEngine policyEngine = defaultPolicyEngine();
    GitHubClient gitHubClient = defaultGitHubClient();

    @BeforeEach
    void setUp() throws Exception {
        workspace = new FixtureWorkspace(Path.of("src/test/resources/fixture"), "orch-test");
        workspace.prepare();
        events = new RunEventPublisher();
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow();
        workspace.cleanup();
    }

    /** Returns a scripted sequence of results; the last entry repeats. */
    static class ScriptedAgent implements Agent {
        private final String name;
        private final List<AgentResult> script;
        private int i = 0;
        int calls = 0;
        ScriptedAgent(String name, List<AgentResult> script) { this.name = name; this.script = script; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            calls++;
            return script.get(Math.min(i++, script.size() - 1));
        }
    }

    static Agent defaultPlanner() {
        return new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                return AgentResult.ok("planner", "plan", List.of(), TokenUsage.NONE);
            }
        };
    }

    static PolicyEngine defaultPolicyEngine() {
        return context -> PolicyResult.ok();
    }

    static GitHubClient defaultGitHubClient() {
        return new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                throw new UnsupportedOperationException("no test using this default expects a push");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("no test using this default expects a PR");
            }
        };
    }

    /** Throws on every call — stands in for a 429 or a dropped connection. */
    static class ExplodingAgent implements Agent {
        private final String name;
        ExplodingAgent(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public AgentResult run(RunState s) {
            throw new RuntimeException("429 rate limited");
        }
    }

    /** In-memory SkillStore double: starts with the given entries, records what gets written. */
    static class FakeSkillStore implements SkillStore {
        final List<SkillIndexEntry> entries;
        final Map<String, String> full = new HashMap<>();
        final List<SkillDraft> written = new ArrayList<>();
        final List<String> readFullCalls = new ArrayList<>();
        final List<String> repoSlugsSeen = new ArrayList<>();
        FakeSkillStore() { this(List.of()); }
        FakeSkillStore(List<SkillIndexEntry> entries) { this.entries = entries; }
        @Override public List<SkillIndexEntry> index(String repoSlug) {
            repoSlugsSeen.add(repoSlug);
            return entries;
        }
        @Override public String readFull(String repoSlug, String name) {
            repoSlugsSeen.add(repoSlug);
            readFullCalls.add(name);
            return full.getOrDefault(name, "");
        }
        @Override public String write(String repoSlug, String runId, SkillDraft draft) {
            repoSlugsSeen.add(repoSlug);
            written.add(draft);
            return SkillFileFormat.render(draft, runId);
        }
    }

    static class FakeMemoryStore implements MemoryStore {
        String content = "";
        final List<String> appended = new ArrayList<>();
        final List<String> repoSlugsSeen = new ArrayList<>();
        @Override public String read(String repoSlug) {
            repoSlugsSeen.add(repoSlug);
            return content;
        }
        @Override public String append(String repoSlug, String fact) {
            repoSlugsSeen.add(repoSlug);
            appended.add(fact);
            return content;
        }
    }

    private Orchestrator orchestrator(Agent coder, Agent reviewer) {
        return orchestrator(coder, reviewer, (task, index) -> List.of(),
                (state, findings, reason) -> ScribeDraft.EMPTY, new FakeSkillStore(), new FakeMemoryStore());
    }

    private Orchestrator orchestrator(Agent coder, Agent reviewer, SkillPicker picker, Scribe scribe,
                                      SkillStore skillStore, MemoryStore memoryStore) {
        return new Orchestrator(coder, reviewer, planner, picker, scribe, skillStore, memoryStore,
                events, 3, 5, Duration.ofMinutes(1), policyEngine, gitHubClient);
    }

    private Orchestrator orchestrator(Agent coder, Agent reviewer, RequirementAnalyst requirementAnalyst) {
        return new Orchestrator(coder, reviewer, planner, (task, index) -> List.of(),
                (state, findings, reason) -> ScribeDraft.EMPTY, new FakeSkillStore(), new FakeMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), policyEngine, gitHubClient,
                (repoUrl, ref, onUpdate) -> { throw new GitHubClientException("CI observation is not configured"); },
                ReleaseDispatcher.disabled(),
                (repoUrl, workflowRunId, onUpdate) -> { throw new GitHubClientException("Release verification is not configured"); },
                RollbackDispatcher.disabled(), OperationalHealthObserver.disabled(), StagingDispatcher.disabled(),
                SmokeTestRunner.disabled(), requirementAnalyst);
    }

    /** Writes a real file so changedFiles() is non-empty, as a real coder would. */
    private ScriptedAgent writingCoder(String summary) {
        return new ScriptedAgent("coder", List.of(
                AgentResult.ok("coder", summary, List.of("scratch.txt"), TokenUsage.NONE))) {
            @Override public AgentResult run(RunState s) {
                try {
                    Files.writeString(s.workspace().root().resolve("scratch.txt"), "written " + calls);
                } catch (Exception e) { throw new RuntimeException(e); }
                return super.run(s);
            }
        };
    }

    /** Runs the orchestrator on a pool, approving every gate as it appears. */
    private Orchestrator.RunOutcome runApprovingAll(Orchestrator orchestrator, RunState state,
                                                    ApprovalGate gate) throws Exception {
        Future<Orchestrator.RunOutcome> outcome = pool.submit(() -> orchestrator.run(state, gate));
        approveGatesUntilDone(gate, outcome);
        return outcome.get(20, TimeUnit.SECONDS);
    }

    private void approveGatesUntilDone(ApprovalGate gate, Future<?> outcome) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!outcome.isDone()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished");
            if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
            Thread.sleep(5);
        }
    }

    @Test
    void approvedRunPassesThroughAllThreeGates() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        var state = new RunState("g1", "do a thing", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).isEqualTo(1);
        assertThat(reviewer.calls).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(RunPhase.DONE);
    }

    @Test
    void requirementAnalysisRunsBeforePlanning() throws Exception {
        var plannerSawRequirements = new java.util.concurrent.atomic.AtomicBoolean();
        planner = new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState state) {
                plannerSawRequirements.set(state.requirementAnalysis() != null);
                return AgentResult.ok("planner", "plan", List.of(), TokenUsage.NONE);
            }
        };
        RequirementAnalysis analysis = readyAnalysis("Given a valid task, when planned, then produce steps");
        RequirementAnalyst analyst = configuredAnalyst(state -> new RequirementAnalysisResult(analysis,
                new TokenUsage(7, 3)));
        var state = new RunState("requirements-before-plan", "do a thing", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(writingCoder("done"), okReviewer(), analyst), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(plannerSawRequirements).isTrue();
        assertThat(state.requirementAnalysis()).isEqualTo(analysis);
        assertThat(state.totalTokens()).isEqualTo(new TokenUsage(7, 3));
    }

    @Test
    void operatorClarificationReRunsRequirementAnalysisBeforePlanning() throws Exception {
        AtomicInteger analystCalls = new AtomicInteger();
        RequirementAnalyst analyst = configuredAnalyst(state -> {
            if (analystCalls.getAndIncrement() == 0) {
                return new RequirementAnalysisResult(new RequirementAnalysis(
                        List.of("FR-1 Add export"), List.of(), List.of(),
                        List.of("Given export, when requested, then return a file"),
                        List.of("Required file format"), true, "Which export format should be used?"),
                        TokenUsage.NONE);
            }
            return new RequirementAnalysisResult(readyAnalysis(
                    "Given CSV export, when requested, then return a CSV file"), TokenUsage.NONE);
        });
        var state = new RunState("requirements-clarified", "add export", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        Future<Orchestrator.RunOutcome> outcome = pool.submit(() ->
                orchestrator(writingCoder("done"), okReviewer(), analyst).run(state, gate));

        while (gate.pending() != Gate.PRE_FLIGHT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());
        while (gate.pending() != Gate.REQUIREMENTS_CLARIFICATION) Thread.sleep(5);
        gate.decide(ApprovalDecision.rejectWith("Use CSV"));
        approveGatesUntilDone(gate, outcome);

        assertThat(outcome.get(10, TimeUnit.SECONDS).approved()).isTrue();
        assertThat(analystCalls).hasValue(2);
        assertThat(state.requirementClarifications()).containsExactly("Use CSV");
        assertThat(state.requirementAnalysis().acceptanceCriteria()).containsExactly(
                "Given CSV export, when requested, then return a CSV file");
    }

    private RequirementAnalysis readyAnalysis(String acceptanceCriterion) {
        return new RequirementAnalysis(List.of("FR-1 Implement the requested behavior"), List.of(), List.of(),
                List.of(acceptanceCriterion), List.of(), false, "");
    }

    private RequirementAnalyst configuredAnalyst(
            java.util.function.Function<RunState, RequirementAnalysisResult> analyze) {
        return new RequirementAnalyst() {
            @Override public boolean isConfigured() { return true; }
            @Override public RequirementAnalysisResult analyze(RunState state) { return analyze.apply(state); }
        };
    }

    @Test
    void rejectingPreFlightAbortsWithoutRunningTheCoder() throws Exception {
        var coder = writingCoder("should never run");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g2", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));
        while (gate.pending() == null) Thread.sleep(5);
        gate.decide(ApprovalDecision.reject());

        assertThat(f.get(10, TimeUnit.SECONDS).approved()).isFalse();
        assertThat(coder.calls).isZero();
    }

    @Test
    void rejectingWithAReasonSendsAHumanFindingBackToTheCoder() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g3", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));

        // Approve pre-flight, then reject the build gate with a reason once.
        while (gate.pending() != Gate.PRE_FLIGHT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());
        while (gate.pending() != Gate.BEFORE_BUILD) Thread.sleep(5);
        gate.decide(ApprovalDecision.rejectWith("use a DTO"));

        // Then approve everything else so the run can finish.
        approveGatesUntilDone(gate, f);
        f.get(20, TimeUnit.SECONDS);

        assertThat(coder.calls)
                .as("human rejection sent work back to the coder")
                .isGreaterThanOrEqualTo(2);
        assertThat(state.humanIterations()).isEqualTo(1);
        assertThat(state.reviewIterations())
                .as("human steering must not consume the reviewer's cap")
                .isLessThanOrEqualTo(2);
    }

    @Test
    void loopsBackToTheCoderThenStopsAtTheReviewCap() throws Exception {
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH,
                        "A.java", 1, "still wrong")), TokenUsage.NONE);
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce));
        var state = new RunState("g4", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(coder.calls).isEqualTo(3);
        assertThat(state.reviewIterations()).isEqualTo(3);
    }

    @Test
    void aFailedReviewIsNotTreatedAsApproval() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(new AgentResult("reviewer", AgentResult.Status.FAILED,
                        "unparseable", List.of(), List.of(), TokenUsage.NONE)));
        var state = new RunState("g5", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        assertThat(runApprovingAll(orchestrator(coder, reviewer), state, gate).approved()).isFalse();
    }

    @Test
    void anExceptionFromAnAgentBecomesAFailedOutcomeNotAnEscapedThrowable() throws Exception {
        var state = new RunState("g6", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(new ExplodingAgent("coder"),
                new ScriptedAgent("reviewer", List.of(
                        AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE))));

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("429");
        assertThat(state.phase()).isEqualTo(RunPhase.FAILED);
    }

    @Test
    void anEmptyChangesetIsNeverApproved() throws Exception {
        // A coder that writes nothing: changedFiles() stays empty. Even if the
        // reviewer says OK (nothing to complain about), approving would claim a
        // change was made and reviewed when neither happened.
        var idleCoder = new ScriptedAgent("coder",
                List.of(AgentResult.ok("coder", "I did nothing", List.of(), TokenUsage.NONE)));
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "nothing to review", List.of(), TokenUsage.NONE)));
        var state = new RunState("g7", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(idleCoder, reviewer), state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).containsIgnoringCase("no changes");
    }

    @Test
    void orchestratorNeverHoldsFileContents() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var state = new RunState("g8", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(state.history()).allSatisfy(r ->
                assertThat(r.summary().length()).isLessThan(2_000));
    }

    @Test
    void loadedSkillsAndMemoryReachTheCoderThroughRunState() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));

        var skillStore = new FakeSkillStore(
                List.of(new SkillIndexEntry("spring-validation", "desc", List.of("validation"))));
        skillStore.full.put("spring-validation", "## Steps\n1. Add @Valid\n");
        var memoryStore = new FakeMemoryStore();
        memoryStore.content = "- tests use JUnit 5";
        SkillPicker picker = (task, index) -> List.of("spring-validation");

        var state = new RunState("g14", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, picker, (s, f, r) -> ScribeDraft.EMPTY, skillStore, memoryStore);

        runApprovingAll(orchestrator, state, gate);

        assertThat(state.memory()).isEqualTo("- tests use JUnit 5");
        assertThat(state.loadedSkills()).containsExactly("## Steps\n1. Add @Valid\n");
    }

    @Test
    void aPickerNameNotInTheIndexIsNeverLoaded() throws Exception {
        // Defense in depth: SkillPickerAgent.parse doesn't validate its own
        // output against the index it was given, so a hallucinated (or
        // otherwise invalid) name must be filtered out here rather than
        // handed to skillStore.readFull.
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));

        var skillStore = new FakeSkillStore(
                List.of(new SkillIndexEntry("real-skill", "desc", List.of("x"))));
        skillStore.full.put("real-skill", "## Steps\n1. Do it\n");
        SkillPicker picker = (task, index) -> List.of("hallucinated-skill");

        var state = new RunState("g21", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, picker, (s, f, r) -> ScribeDraft.EMPTY, skillStore, new FakeMemoryStore());

        runApprovingAll(orchestrator, state, gate);

        assertThat(state.loadedSkills()).isEmpty();
        assertThat(skillStore.readFullCalls)
                .as("a name not in the index must be filtered out before ever reaching readFull")
                .doesNotContain("hallucinated-skill");
    }

    @Test
    void emptySkillIndexNeverInvokesThePicker() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        SkillPicker explodingPicker = (task, index) -> { throw new AssertionError("picker should not be called"); };

        var state = new RunState("g15", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, explodingPicker, (s, f, r) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore());

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
    }

    /** Approves every gate up to (but not including) the given gate, then returns with that gate pending. */
    private void approveUntil(ApprovalGate gate, Future<?> outcome, Gate target) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (gate.pending() != target) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("gate " + target + " never arrived");
            if (gate.pending() != null) gate.decide(ApprovalDecision.approve());
            Thread.sleep(5);
        }
    }

    @Test
    void cleanFirstPassRunNeverInvokesTheScribe() throws Exception {
        var coder = writingCoder("done");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        Scribe explodingScribe = (state, findings, reason) -> { throw new AssertionError("scribe should not be called"); };

        var state = new RunState("g16", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), explodingScribe,
                new FakeSkillStore(), new FakeMemoryStore());

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
    }

    @Test
    void reviewerBounceTwiceTriggersSkillExtractionOnApproval() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));
        Scribe scribe = (state, findings, reason) ->
                new ScribeDraft(new SkillDraft("bounce-lesson", "d", List.of("x"), "body"), "tests use JUnit 5");
        var skillStore = new FakeSkillStore();
        var memoryStore = new FakeMemoryStore();

        var state = new RunState("g17", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, memoryStore);

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(state.reviewIterations()).isEqualTo(2);
        assertThat(skillStore.written).extracting(SkillDraft::name).containsExactly("bounce-lesson");
        assertThat(memoryStore.appended).containsExactly("tests use JUnit 5");
    }

    @Test
    void repoSlugFromRunStateThreadsThroughToTheStores() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));
        Scribe scribe = (state, findings, reason) ->
                new ScribeDraft(new SkillDraft("s", "d", List.of(), "body"), "a fact");
        var skillStore = new FakeSkillStore();
        var memoryStore = new FakeMemoryStore();

        var state = new RunState("g21", "t", workspace, "repo-x");
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, memoryStore);

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(skillStore.repoSlugsSeen).containsOnly("repo-x");
        assertThat(memoryStore.repoSlugsSeen).containsOnly("repo-x");
    }

    // Regression for this plan's design note #5: decrementReviewIteration
    // (Phase 4) rolls reviewIterations back on every human correction so it
    // doesn't consume the reviewer's separate cap -- which means a purely
    // human-corrected run can finish with reviewIterations stuck at 1,
    // never reaching a literal ">= 2". The trigger must also check
    // humanIterations, or a human's own correction would never be learned.
    @Test
    void humanCorrectionAloneTriggersSkillExtractionEvenThoughReviewIterationsStaysAtOne() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        Scribe scribe = (state, findings, reason) ->
                new ScribeDraft(new SkillDraft("human-taught", "d", List.of(), "body"), null);
        var skillStore = new FakeSkillStore();

        var state = new RunState("g18", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, new FakeMemoryStore());

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator.run(state, gate));
        while (gate.pending() != Gate.PRE_FLIGHT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());
        while (gate.pending() != Gate.BEFORE_BUILD) Thread.sleep(5);
        gate.decide(ApprovalDecision.rejectWith("use a DTO"));

        approveGatesUntilDone(gate, f);
        f.get(20, TimeUnit.SECONDS);

        assertThat(state.reviewIterations())
                .as("the rollback that protects the reviewer's cap must not also hide a human correction from the Scribe")
                .isEqualTo(1);
        assertThat(state.humanIterations()).isEqualTo(1);
        assertThat(skillStore.written).extracting(SkillDraft::name).containsExactly("human-taught");
    }

    @Test
    void gate3RejectWithReasonRerunsOnlyTheScribeNotTheCoder() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));

        var scribeCalls = new AtomicInteger(0);
        Scribe scribe = (state, findings, reason) -> {
            int n = scribeCalls.incrementAndGet();
            String name = n == 1 ? "first-draft" : "revised-draft";
            return new ScribeDraft(new SkillDraft(name, "d", List.of(), "body"), null);
        };
        var skillStore = new FakeSkillStore();

        var state = new RunState("g19", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, new FakeMemoryStore());

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator.run(state, gate));
        approveUntil(gate, f, Gate.BEFORE_COMMIT);
        gate.decide(ApprovalDecision.rejectWith("wrong lesson"));
        while (gate.pending() != Gate.BEFORE_COMMIT) Thread.sleep(5);
        gate.decide(ApprovalDecision.approve());

        var outcome = f.get(20, TimeUnit.SECONDS);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls).as("Gate 3's retry must re-run only the Scribe, not the coder").isEqualTo(2);
        assertThat(scribeCalls.get()).isEqualTo(2);
        assertThat(skillStore.written).extracting(SkillDraft::name).containsExactly("revised-draft");
    }

    @Test
    void gate3RejectWithoutReasonCommitsCodeButDiscardsTheDraft() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));
        Scribe scribe = (state, findings, reason) ->
                new ScribeDraft(new SkillDraft("a-lesson", "d", List.of(), "body"), null);
        var skillStore = new FakeSkillStore();

        var state = new RunState("g20", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));
        var orchestrator = orchestrator(coder, reviewer, (task, index) -> List.of(), scribe, skillStore, new FakeMemoryStore());

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator.run(state, gate));
        approveUntil(gate, f, Gate.BEFORE_COMMIT);
        gate.decide(ApprovalDecision.reject()); // no reason

        var outcome = f.get(20, TimeUnit.SECONDS);

        assertThat(outcome.approved())
                .as("Gate 3 alone commits validated code even on a bare rejection -- spec §5.2's Gate-3 row")
                .isTrue();
        assertThat(outcome.reason())
                .as("the outcome reason must reflect that the lesson was declined, not claim full approval")
                .containsIgnoringCase("declined");
        assertThat(skillStore.written).isEmpty();
    }

    @Test
    void plannerRunsExactlyOnceEvenAcrossMultipleReviewIterations() throws Exception {
        var coder = writingCoder("attempt");
        var bounce = AgentResult.needsWork("reviewer", "nope",
                List.of(new Finding(Finding.Origin.REVIEWER, Finding.Severity.HIGH, "A.java", 1, "still wrong")), TokenUsage.NONE);
        var ok = AgentResult.ok("reviewer", "looks good now", List.of(), TokenUsage.NONE);
        var reviewer = new ScriptedAgent("reviewer", List.of(bounce, ok));
        var plannerCalls = new AtomicInteger(0);
        planner = new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                plannerCalls.incrementAndGet();
                return AgentResult.ok("planner", "1. Add validation\n2. Add a test", List.of(), TokenUsage.NONE);
            }
        };
        var state = new RunState("g22", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(state.reviewIterations()).isEqualTo(2);
        assertThat(plannerCalls.get())
                .as("planner is a once-per-run artifact, not re-derived each review iteration")
                .isEqualTo(1);
        assertThat(state.plan()).isEqualTo("1. Add validation\n2. Add a test");
    }

    @Test
    void plannerNeverRunsIfPreFlightIsRejected() throws Exception {
        var coder = writingCoder("should never run");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "ok", List.of(), TokenUsage.NONE)));
        var plannerCalls = new AtomicInteger(0);
        planner = new Agent() {
            @Override public String name() { return "planner"; }
            @Override public AgentResult run(RunState s) {
                plannerCalls.incrementAndGet();
                return AgentResult.ok("planner", "p", List.of(), TokenUsage.NONE);
            }
        };
        var state = new RunState("g23", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        Future<Orchestrator.RunOutcome> f = pool.submit(() -> orchestrator(coder, reviewer).run(state, gate));
        while (gate.pending() == null) Thread.sleep(5);
        gate.decide(ApprovalDecision.reject());

        assertThat(f.get(10, TimeUnit.SECONDS).approved()).isFalse();
        assertThat(plannerCalls.get()).isZero();
    }

    @Test
    void aFailingPolicyCheckSendsItBackToTheCoderThenRecovers() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        var policyCalls = new AtomicInteger(0);
        policyEngine = context -> {
            int n = policyCalls.incrementAndGet();
            return n == 1 ? new PolicyResult(false, "the build did not pass") : PolicyResult.ok();
        };
        var state = new RunState("g24", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(coder.calls)
                .as("a failed policy check must send the run back to the coder")
                .isEqualTo(2);
        assertThat(state.reviewIterations()).isEqualTo(2);
        assertThat(policyCalls.get()).isEqualTo(2);
    }

    @Test
    void policyFailingEveryIterationExhaustsTheCapWithoutApproval() throws Exception {
        var coder = writingCoder("attempt");
        var reviewer = new ScriptedAgent("reviewer",
                List.of(AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE)));
        policyEngine = context -> new PolicyResult(false, "the build did not pass");
        var state = new RunState("g25", "t", workspace);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator(coder, reviewer), state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(coder.calls).isEqualTo(3);
        assertThat(outcome.reason())
                .as("a policy-driven cap-out must say so, not blame the review loop generically")
                .contains("policy");
    }

    @Test
    void openPrPushesThenOpensAPrAndTheDoneEventCarriesItsUrl() throws Exception {
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        var pushed = new AtomicInteger(0);
        GitHubClient fakeClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) {
                pushed.incrementAndGet();
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                return new PullRequestResult("https://github.com/o/r/pull/7", 7);
            }
        };
        CiObserver passedObserver = (repoUrl, ref, onUpdate) -> {
            var observation = new CiObservation(CiStatus.PASSED, List.of());
            onUpdate.accept(observation);
            return observation;
        };
        var orchestrator = new Orchestrator(writingCoder("done"), okReviewer(), planner,
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore(),
                recordingEvents, 3, 5, Duration.ofMinutes(1), policyEngine, fakeClient, passedObserver);
        var state = new RunState("pr1", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(pushed.get()).isEqualTo(1);
        var doneEvent = recordedEvents(captured).stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).containsEntry("prUrl", "https://github.com/o/r/pull/7")
                .containsEntry("ciStatus", "PASSED");
        assertThat(recordedEvents(captured)).anyMatch(event ->
                "VALIDATING_CI".equals(event.data().get("phase")));
    }

    @Test
    void aPushFailureIsReportedAsALostCommitAndTheRunFails() throws Exception {
        GitHubClient failingClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) throws GitHubClientException {
                throw new GitHubClientException("network unreachable");
            }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                throw new UnsupportedOperationException("push already failed");
            }
        };
        var orchestrator = new Orchestrator(writingCoder("done"), okReviewer(), planner,
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore(),
                events, 3, 5, Duration.ofMinutes(1), policyEngine, failingClient);
        var state = new RunState("pr2", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isFalse();
        assertThat(outcome.reason()).contains("Push failed").contains("now lost");
    }

    @Test
    void aPrCreationFailureStillEndsTheRunDone() throws Exception {
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        GitHubClient pushOnlyClient = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body)
                    throws GitHubClientException {
                throw new GitHubClientException("insufficient permissions");
            }
        };
        var orchestrator = new Orchestrator(writingCoder("done"), okReviewer(), planner,
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore(),
                recordingEvents, 3, 5, Duration.ofMinutes(1), policyEngine, pushOnlyClient);
        var state = new RunState("pr3", "t", workspace, "fixture", true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
        var events = recordedEvents(captured);
        var doneEvent = events.stream()
                .filter(e -> "done".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(doneEvent.data()).doesNotContainKey("prUrl");
        assertThat(events.stream().anyMatch(e ->
                "warn".equals(e.type()) && e.message().contains("Open it manually"))).isTrue();
    }

    @Test
    void requestedReleaseDispatchesAfterTheExistingGatesAndReleaseApproval() throws Exception {
        var captured = new ArrayList<Object>();
        var recordingEvents = new RunEventPublisher(captured::add);
        var dispatched = new AtomicInteger();
        GitHubClient client = new GitHubClient() {
            @Override public void push(ai.devflow.workspace.Workspace workspace, String branchName) { }
            @Override public PullRequestResult openPullRequest(String repoUrl, String branchName, String title, String body) {
                return new PullRequestResult("https://github.com/o/r/pull/12", 12);
            }
            @Override public boolean isPullRequestMerged(String repoUrl, int pullRequestNumber) { return true; }
            @Override public ai.devflow.tools.WorkflowDispatchResult dispatchWorkflow(String repoUrl, String workflow, String ref) {
                dispatched.incrementAndGet();
                return new ai.devflow.tools.WorkflowDispatchResult(9L, "https://github.com/o/r/actions/runs/9");
            }
        };
        CiObserver passedObserver = (repoUrl, ref, onUpdate) -> {
            var observation = new CiObservation(CiStatus.PASSED, List.of());
            onUpdate.accept(observation);
            return observation;
        };
        ReleaseObserver passedReleaseObserver = (repoUrl, workflowRunId, onUpdate) -> {
            var observation = new ReleaseObservation(VerificationStatus.PASSED,
                    new ai.devflow.tools.WorkflowRun("completed", "success", "https://github.com/o/r/actions/runs/9"));
            onUpdate.accept(observation);
            return observation;
        };
        var orchestrator = new Orchestrator(writingCoder("done"), okReviewer(), planner,
                (task, index) -> List.of(), (state, findings, reason) -> ScribeDraft.EMPTY,
                new FakeSkillStore(), new FakeMemoryStore(), recordingEvents, 3, 5, Duration.ofMinutes(1),
                policyEngine, client, passedObserver, new GitHubActionsReleaseDispatcher(client, "release.yml", "main"),
                passedReleaseObserver);
        var state = new RunState("pr4", "t", workspace, "fixture", true, true);
        var gate = new ApprovalGate(Duration.ofSeconds(10));

        var outcome = runApprovingAll(orchestrator, state, gate);

        assertThat(outcome.approved()).isTrue();
        assertThat(dispatched).hasValue(1);
        var doneEvent = recordedEvents(captured).stream().filter(event -> "done".equals(event.type())).findFirst().orElseThrow();
        assertThat(doneEvent.data()).containsEntry("releaseStatus", "DISPATCHED")
                .containsEntry("releaseUrl", "https://github.com/o/r/actions/runs/9")
                .containsEntry("verificationStatus", "PASSED");
    }

    private List<ai.devflow.event.RunEvent> recordedEvents(List<Object> captured) {
        return captured.stream()
                .filter(ai.devflow.event.RunRecorded.class::isInstance)
                .map(ai.devflow.event.RunRecorded.class::cast)
                .map(ai.devflow.event.RunRecorded::event)
                .toList();
    }

    private Agent okReviewer() {
        return new Agent() {
            @Override public String name() { return "reviewer"; }
            @Override public AgentResult run(RunState s) {
                return AgentResult.ok("reviewer", "looks good", List.of(), TokenUsage.NONE);
            }
        };
    }
}
