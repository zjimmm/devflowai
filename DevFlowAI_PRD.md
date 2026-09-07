# DevFlowAI — Product Requirements Document

**Product:** DevFlowAI  
**Document:** Product Requirements Document (PRD)  
**Status:** Draft  
**Primary Stack:** Java / Spring Boot / Spring AI  
**Product Type:** AI-native SDLC orchestration and governance platform

---

## 1. Executive Summary

DevFlowAI is an **AI-powered SDLC control plane** that takes a software requirement from intake to a production-ready change by orchestrating coding agents, specialized AI roles, engineering tools, quality gates, human approvals, and delivery workflows.

DevFlowAI is **not intended to replace coding agents such as Codex or Claude Code**. Modern coding agents are already strong at repository exploration, implementation, terminal usage, testing, review, and Git operations.

Instead, DevFlowAI coordinates those agents inside a **controlled, auditable, policy-driven software development lifecycle**.

The platform's core responsibility is to ensure that:

- the correct SDLC stages execute in the correct order;
- each stage uses the appropriate model, agent, or engineering tool;
- mandatory quality and security gates cannot be silently skipped;
- human approval is required at configured checkpoints;
- every action is recorded as part of an inspectable SDLC run;
- cost, token usage, failures, retries, and results can be measured;
- teams can compare direct coding-agent execution with orchestrated workflows.

### Product vision

> **An AI-native SDLC orchestration and governance platform that coordinates coding agents, engineering tools, quality gates, and human approvals from requirement to production.**

---

## 2. Background

The original DevFlowAI concept described a crew of specialized AI agents collaborating on a development task through a workflow such as:

```text
Plan → Code → Review → Test → Document
```

The orchestrator routes work between agents, provides tools for real repository operations, supports bounded review/fix loops, streams progress, and pauses before risky actions.

That foundation remains useful, but the product should evolve beyond being a multi-agent coding demo.

Modern coding agents increasingly perform planning, implementation, testing, review, shell execution, Git operations, and subagent orchestration themselves.

Therefore, DevFlowAI should focus on the layer **above** the coding agent:

```text
Requirement / Ticket
        ↓
     DevFlowAI
        ↓
SDLC Policy + Workflow
        ↓
Codex / Claude / Other Worker
        ↓
Engineering Tools
        ↓
Quality Gates
        ↓
Human Approvals
        ↓
Delivery
```

This makes DevFlowAI complementary to coding agents rather than a competing coding interface.

---

## 3. Problem Statement

Developers can already ask an AI coding agent to implement a feature, test it, review it, and create a diff.

However, a prompt such as:

> "Implement this feature, run tests, review your work, fix issues, and update the docs."

is still a **request**, not an enforced software development process.

The agent can decide how to interpret the instruction, how deeply to review its own work, which checks to run, and whether intermediate outputs are preserved.

This creates several problems for teams adopting autonomous coding agents.

### 3.1 Process enforcement

Organizations need required development stages to happen consistently.

Examples:

- architecture review for major changes;
- mandatory unit tests;
- security scanning;
- coverage thresholds;
- independent code review;
- human approval before creating or merging a PR;
- production approval before deployment.

These steps should be enforced by software rather than left entirely to prompt compliance.

### 3.2 Auditability

Teams need to answer:

- What requirement triggered this change?
- Which model or agent implemented it?
- Which files changed?
- Which tests ran?
- What review findings were discovered?
- What fixes were applied?
- Who approved the change?
- What did the AI execution cost?

A terminal session alone is not an ideal persistent system of record for the full SDLC.

### 3.3 Model and tool specialization

One model may be best for coding while another is better for review, requirement analysis, security reasoning, or low-cost routing.

Teams need a mechanism to choose the right worker for each stage.

### 3.4 Controlled autonomy

Organizations need human-in-the-loop checkpoints around risky or consequential operations.

### 3.5 Evaluation

Teams need evidence for whether multi-stage orchestration improves software quality enough to justify additional latency and cost versus using a coding agent directly.

---

## 4. Product Vision

DevFlowAI should become the **control plane for autonomous software development**.

It should coordinate:

- requirements;
- planning;
- architecture;
- implementation;
- builds;
- testing;
- review;
- security;
- documentation;
- approvals;
- pull requests;
- CI/CD;
- deployment;
- post-deployment verification.

DevFlowAI owns the workflow.

Coding agents own implementation work.

External engineering tools remain the source of truth for deterministic checks such as builds, tests, static analysis, Git status, CI, and deployment state.

---

## 5. Goals

### 5.1 Primary goals

DevFlowAI must:

1. Take a development task from requirement intake through a controlled SDLC workflow.
2. Produce real repository artifacts such as a branch, commit, or diff.
3. Integrate strong external coding agents rather than rebuilding all coding capabilities internally.
4. Enforce configurable engineering quality gates.
5. Support bounded review → fix loops.
6. Require human approval at configurable checkpoints.
7. Persist the complete lifecycle as an inspectable SDLC run.
8. Record model usage, tool execution, cost, duration, retries, approvals, and outputs.
9. Support model and worker routing by workflow stage.
10. Provide an evaluation mode that compares direct coding-agent execution with DevFlowAI orchestration.
11. Remain realistic to build incrementally as a solo Java/Spring project.

### 5.2 Secondary goals

DevFlowAI should eventually support:

- Jira / GitHub Issues / Linear intake;
- multiple repositories;
- team-level policy configuration;
- CI/CD integrations;
- deployment orchestration;
- dashboards;
- role-based approvals;
- benchmark datasets;
- model performance comparisons.

---

## 6. Non-Goals

DevFlowAI is not intended to:

1. Build a general-purpose IDE.
2. Replace Codex, Claude Code, or similar coding agents.
3. Train foundation models.
4. Build a new Git hosting provider.
5. Reimplement mature CI/CD systems.
6. Replace deterministic static-analysis, test, or security tools with AI.
7. Fully automate production deployment without configurable approval controls.
8. Guarantee that multi-agent orchestration always performs better than a single coding agent.
9. Implement all SDLC stages in the first release.
10. Support every programming language or build system in the MVP.

---

## 7. Target Users

### 7.1 Primary Persona — Senior Software Engineer

A senior engineer experimenting with autonomous coding agents who wants stronger control over how AI-generated code moves through the development process.

Needs:

- reliable orchestration;
- real repository changes;
- visibility into agent actions;
- configurable review and test loops;
- cost awareness;
- approval gates;
- reproducibility.

### 7.2 Secondary Persona — Engineering Lead

An engineering lead evaluating how coding agents could be used safely within team workflows.

Needs:

- standard SDLC policy;
- audit trails;
- quality gates;
- independent review;
- measurable outcomes;
- comparison between models and workflows.

### 7.3 Future Persona — Platform / DevEx Team

A platform team that wants to expose coding agents through a governed internal development platform.

Needs:

- centralized configuration;
- multiple repositories;
- role-based controls;
- CI/CD integration;
- model routing;
- policy enforcement;
- analytics.

---

## 8. Key Use Cases

### UC-1 — Feature Implementation

A user submits a feature request.

DevFlowAI analyzes the requirement, creates an implementation plan, delegates coding, runs validation, sends the result through independent review, and produces an approved PR-ready change.

### UC-2 — Bug Fix

A user submits a defect description.

DevFlowAI reproduces or analyzes the failure, delegates the fix, executes tests, reviews the diff, and validates that the regression is covered.

### UC-3 — Security-Sensitive Change

A task is classified as security-sensitive.

DevFlowAI automatically adds mandatory security review and stricter approval gates.

### UC-4 — Architecture Change

A task affects public APIs, database schema, module boundaries, or dependencies.

DevFlowAI requires architecture review before implementation.

### UC-5 — Direct vs Orchestrated Evaluation

A user runs the same task:

- directly through a coding agent;
- through DevFlowAI's orchestrated SDLC.

DevFlowAI compares quality, cost, latency, test success, review findings, and human intervention.

### UC-6 — CI Failure Remediation

A PR generated by DevFlowAI fails CI.

The platform may start a bounded remediation loop, re-run validation, and update the existing change.

---

## 9. Product Principles

### 9.1 Orchestration over replacement

Use capable coding workers instead of reproducing their functionality.

### 9.2 Deterministic tools for deterministic facts

If the build can answer whether code compiles, run the build.

If the test suite can answer whether tests pass, run the tests.

AI should interpret, plan, critique, and decide—not pretend to execute tools.

### 9.3 Workflow policy over prompt suggestions

Important SDLC requirements should exist in software configuration.

### 9.4 Independent verification

The implementation agent should not be the only authority deciding whether its own output is correct.

### 9.5 Controlled autonomy

Human approvals remain mandatory where risk or organizational policy requires them.

### 9.6 Bounded loops

Review/fix loops must have configurable maximum iterations to prevent uncontrolled cost and latency.

### 9.7 Inspectability

Every important decision and artifact should be traceable.

### 9.8 Measure before claiming improvement

DevFlowAI should benchmark orchestration against direct coding-agent usage rather than assuming multi-agent workflows are always superior.

---

## 10. Core User Experience

The user opens DevFlowAI and selects a repository.

They enter a development task such as:

> Add account locking after five failed login attempts and cover the behavior with tests.

DevFlowAI creates an **SDLC Run**.

The UI shows each lifecycle stage as it executes.

Example:

```text
RUN-4821

✓ Requirement Analysis
✓ Planning
✓ Architecture
✓ Implementation
✓ Build
✓ Tests
✓ Review
↻ Fix #1
✓ Build
✓ Tests
✓ Review
✓ Security
✓ Documentation
⏸ Human Approval
○ Pull Request
○ CI
○ Deployment
○ Verification
```

The user can inspect:

- stage inputs;
- agent output;
- tool calls;
- logs;
- changed files;
- review findings;
- test results;
- token usage;
- estimated cost.

Before configured actions such as PR creation or deployment, the workflow pauses for approval.

---

## 11. Full SDLC Lifecycle

The long-term workflow is:

```text
Requirement / Ticket
        ↓
1. Requirement Analysis
        ↓
2. Planning
        ↓
3. Architecture / Design
        ↓
4. Implementation
        ↓
5. Build / Compile
        ↓
6. Automated Testing
        ↓
7. Independent Code Review
        ↓
8. Security / Quality Analysis
        ↓
9. Controlled Fix Loop
        ↓
10. Documentation
        ↓
11. Human Approval
        ↓
12. Pull Request
        ↓
13. CI/CD Validation
        ↓
14. Release / Deployment Approval
        ↓
15. Post-Deployment Verification
```

Not every task must use every stage.

The orchestrator must support conditional routing.

Examples:

```text
Documentation-only task
→ skip implementation build/test stages when not applicable.

Small bug fix
→ architecture stage may be skipped.

Database migration
→ architecture + migration validation required.

Security-sensitive change
→ security stage mandatory.
```

---

## 12. Stage Requirements

### 12.1 Requirement Intake

#### Purpose

Capture the development request and create the root SDLC run.

#### Inputs

- free-text task;
- GitHub Issue;
- future Jira ticket;
- future Linear issue.

#### Outputs

- task ID;
- normalized task description;
- repository;
- branch/workspace;
- SDLC run.

#### Requirements

- User must be able to enter a plain-language development task.
- User must select a target repository.
- DevFlowAI must create a unique SDLC run.
- Original task text must remain preserved in the run history.

---

### 12.2 Requirement Analysis

#### Purpose

Convert an informal request into structured requirements.

#### Example output

```text
Functional Requirements

FR-1
Track consecutive failed authentication attempts.

FR-2
Lock the account after five failures.

FR-3
Reset failed attempts after successful authentication.

FR-4
Locked accounts cannot authenticate.
```

The stage should also identify:

- assumptions;
- edge cases;
- acceptance criteria;
- missing information.

If a requirement is materially ambiguous, DevFlowAI should support pausing the workflow for human clarification.

---

### 12.3 Planning

#### Purpose

Inspect the repository and produce a concrete implementation plan.

#### Example

```text
User.java
- add failedLoginAttempts
- add accountLocked

AuthenticationService.java
- increment attempts
- enforce account locking

LoginController.java
- return locked-account response

AuthenticationServiceTest.java
- add account lockout tests
```

The planner should have read-only repository access.

The output should include:

- affected modules;
- likely affected files;
- expected API changes;
- expected schema changes;
- testing strategy;
- notable risks.

---

### 12.4 Architecture / Design Review

#### Purpose

Determine whether the proposed plan follows project architecture and engineering conventions.

#### Example output

```text
✓ Uses existing service layer
✓ Keeps business logic out of controller
✓ Reuses current DTO patterns
! Database migration required
✓ No new dependency required
```

Architecture review should be configurable and may be required for:

- cross-module changes;
- schema changes;
- new external dependencies;
- security-sensitive features;
- public API changes.

---

### 12.5 Implementation

#### Purpose

Produce the actual repository changes.

DevFlowAI should expose a worker abstraction such as:

```text
CodingWorker
```

Potential implementations:

- Codex;
- Claude Code;
- API-driven coding agent;
- Spring AI coding agent;
- future providers.

#### Inputs

The coding worker receives:

- requirement;
- acceptance criteria;
- implementation plan;
- architecture constraints;
- repository;
- workspace;
- relevant project instructions.

#### Outputs

- changed files;
- diff;
- worker summary;
- tool activity;
- workspace state.

The implementation stage should operate in an isolated branch or worktree.

---

### 12.6 Build Gate

#### Purpose

Verify that the repository builds.

Examples:

```bash
./mvnw clean verify
```

```bash
npm run build
```

#### Requirements

- Build command must be repository-configurable.
- Build exit code must be captured.
- Logs must be persisted.
- Failed builds must block the workflow.
- Failed builds may return to implementation.
- Retry count must be bounded.

---

### 12.7 Automated Testing

#### Purpose

Verify implementation behavior and identify missing test coverage.

Responsibilities:

- inspect existing tests;
- determine required new tests;
- generate tests where appropriate;
- run tests;
- evaluate acceptance criteria.

#### Example output

```text
Unit tests:         48 / 48 PASS
Integration tests: 12 / 12 PASS

New scenarios:
✓ lock after five failures
✓ successful login resets attempts
✓ locked account rejected
```

Test execution must use real project commands.

---

### 12.8 Independent Code Review

#### Purpose

Critique the implementation independently of the coding worker.

Review dimensions:

- correctness;
- bugs;
- regressions;
- architecture;
- maintainability;
- concurrency;
- error handling;
- API behavior;
- edge cases;
- project conventions.

#### Example finding

```text
Severity: HIGH

AuthenticationService resets attempts before checking
whether the account is already locked.

Recommendation:
Check accountLocked before authentication.
```

Findings should contain:

- severity;
- category;
- description;
- affected file/location where available;
- recommendation;
- disposition.

Blocking findings return the workflow to implementation.

---

### 12.9 Security / Quality Gate

#### Purpose

Combine deterministic engineering tools and AI review.

Potential checks:

- static analysis;
- lint;
- secret detection;
- dependency vulnerabilities;
- security review;
- coverage threshold;
- complexity threshold;
- style compliance.

Example policy:

```text
Build                 MUST PASS
Tests                 MUST PASS
Critical security     MUST = 0
High security         MUST = 0
Coverage              MUST >= 80%
Reviewer              MUST APPROVE
```

Policy failures block progression.

---

### 12.10 Controlled Fix Loop

The platform must support:

```text
Implementation
      ↓
Build
      ↓
Tests
      ↓
Review
      ↓
Blocking issue?
   YES ↓
    Fix
      ↓
Build → Tests → Review
```

Example configuration:

```text
maxReviewIterations = 3
```

If the limit is exceeded:

```text
STATUS = HUMAN_INTERVENTION_REQUIRED
```

The platform must never enter an unbounded autonomous loop.

---

### 12.11 Documentation

#### Purpose

Update relevant documentation after code stabilizes.

Possible artifacts:

- README;
- API documentation;
- CHANGELOG;
- migration notes;
- architecture documentation;
- release notes.

This stage should be conditional.

---

### 12.12 Human Approval

Before configured external or risky operations, the workflow pauses.

Example:

```text
DEVFLOWAI RUN #4821

Requirement       ✓
Plan              ✓
Architecture      ✓
Implementation    ✓
Build             ✓
Tests             ✓
Review            ✓
Security          ✓
Documentation     ✓

Files changed: 8
Tests: 60 / 60
Coverage: 86%
Review issues: 3 found / 3 resolved
Security issues: 0
Estimated AI cost: $1.84

[Approve PR]
[Reject]
[Inspect Diff]
```

Approvals must record:

- approver;
- timestamp;
- decision;
- optional comment.

---

### 12.13 Pull Request Creation

After approval, DevFlowAI may create a pull request.

Generated PR data may include:

```text
Title:
feat: add account lockout protection

Summary:
- Added failed-attempt tracking
- Added account locking
- Reset failures after successful authentication

Testing:
60 tests passed

AI Review:
3 findings identified and resolved

Security:
No high or critical findings
```

The generated PR should link back to the SDLC run.

---

### 12.14 CI/CD Validation

DevFlowAI should observe configured CI checks.

Example:

```text
GitHub Actions

Build             ✓
Unit Tests        ✓
Integration Tests ✓
Security Scan     ✓
Static Analysis   ✓
```

A failed pipeline may optionally trigger a controlled remediation run.

CI results must remain distinct from locally executed validation.

---

### 12.15 Deployment / Release Approval

Long-term production workflow:

```text
PR merged
    ↓
Deploy staging
    ↓
Smoke tests
    ↓
Human approval
    ↓
Deploy production
```

Production deployment must be governed by policy.

---

### 12.16 Post-Deployment Verification

Final lifecycle verification may include:

- health endpoint;
- smoke tests;
- API contract checks;
- deployment status;
- log checks;
- error-rate checks.

Successful verification moves the SDLC run to:

```text
COMPLETED
```

---

## 13. Core Domain Model

The central domain object is:

```text
SdlcRun
```

Example:

```text
RUN-4821

Task:
Add account lockout

Status:
WAITING_FOR_APPROVAL

Repository:
auth-service

Stages:
✓ REQUIREMENT
✓ PLANNING
✓ ARCHITECTURE
✓ IMPLEMENTATION
✓ BUILD
✓ TEST
✓ REVIEW
✓ SECURITY
✓ DOCUMENTATION
⏸ HUMAN_APPROVAL
○ PULL_REQUEST
○ CI
○ DEPLOYMENT
○ VERIFICATION
```

### 13.1 Suggested Entities

#### SdlcRun

```text
id
task
repositoryId
status
currentStage
createdAt
startedAt
completedAt
totalTokens
estimatedCost
createdBy
```

#### StageExecution

```text
id
runId
stageType
status
attempt
worker
model
startedAt
completedAt
input
output
tokens
estimatedCost
```

#### ToolExecution

```text
id
stageExecutionId
tool
command
exitCode
stdoutReference
stderrReference
startedAt
completedAt
```

#### ReviewFinding

```text
id
runId
stageExecutionId
severity
category
description
file
line
recommendation
status
```

#### Approval

```text
id
runId
stage
approver
decision
comment
createdAt
```

#### Artifact

```text
id
runId
stageExecutionId
type
name
location
metadata
```

---

## 14. Workflow Engine

The workflow engine is the core of DevFlowAI.

Responsibilities:

- determine which stages apply;
- enforce stage ordering;
- persist workflow state;
- dispatch work to agents and tools;
- handle success/failure transitions;
- enforce retry limits;
- pause for human approvals;
- resume paused runs;
- emit live progress events;
- calculate aggregate cost and duration.

### 14.1 Suggested stage states

```text
PENDING
RUNNING
PASSED
FAILED
SKIPPED
WAITING_FOR_APPROVAL
HUMAN_INTERVENTION_REQUIRED
CANCELLED
```

### 14.2 Suggested run states

```text
CREATED
RUNNING
WAITING_FOR_INPUT
WAITING_FOR_APPROVAL
FAILED
HUMAN_INTERVENTION_REQUIRED
COMPLETED
CANCELLED
```

---

## 15. Agent and Model Router

DevFlowAI should not assume that one model is optimal for all tasks.

Example routing:

```text
Requirement Analysis → lower-cost reasoning model
Routing              → lower-cost model
Planning             → strong reasoning model
Implementation       → Codex / Claude Code
Review               → independent strong model
Security Review      → strong reasoning model
Documentation        → lower-cost model
```

The routing layer should eventually consider:

- stage type;
- task complexity;
- repository language;
- cost;
- latency;
- provider availability;
- historical benchmark performance.

### MVP requirement

Routing may initially be static configuration.

Dynamic routing is a later capability.

---

## 16. Coding Worker Integration

DevFlowAI should define a provider-neutral interface.

Conceptually:

```java
public interface CodingWorker {
    WorkerResult execute(WorkerRequest request);
}
```

A worker request may contain:

```text
repository
workspace
task
requirements
acceptanceCriteria
plan
constraints
allowedTools
timeout
```

A worker result may contain:

```text
status
summary
changedFiles
diff
commandsExecuted
tokenUsage
cost
```

This abstraction is important because the product should remain independent of any single coding-agent provider.

---

## 17. Tool Execution

DevFlowAI must support real engineering tools.

Initial tools:

- filesystem read;
- filesystem write;
- Git status;
- Git diff;
- Git branch/worktree management;
- build command;
- test command.

Later tools:

- static-analysis command;
- dependency scanner;
- secret scanner;
- coverage tool;
- GitHub API;
- CI API;
- deployment API.

### Tool security

Tools should be:

- allowlisted;
- scoped to a workspace;
- logged;
- time-bounded;
- protected by approval policy where appropriate.

---

## 18. Policy Engine

The policy engine defines what must occur before a workflow may advance.

Example:

```yaml
quality:
  buildRequired: true
  testsRequired: true
  minimumCoverage: 80

review:
  required: true
  maxIterations: 3

security:
  required: true
  criticalAllowed: 0
  highAllowed: 0

humanApproval:
  beforePullRequest: true
  beforeProduction: true
```

Policies may eventually vary by:

- repository;
- team;
- branch;
- risk classification;
- task type.

Examples:

```text
Prototype repository
→ lightweight workflow.

Production banking service
→ architecture + security + human review mandatory.
```

---

## 19. Risk Classification

DevFlowAI should eventually classify tasks by risk.

Example:

```text
LOW
MEDIUM
HIGH
CRITICAL
```

Signals may include:

- authentication/authorization changes;
- payments;
- public APIs;
- database migrations;
- infrastructure;
- secrets/configuration;
- dependency changes;
- cross-module scope;
- production configuration.

Risk classification influences required stages and approvals.

### MVP

Risk classification may be manually configured or rule-based.

---

## 20. Audit Trail

Every SDLC run should provide an immutable execution history.

Example:

```text
10:02 Run created
10:03 Requirement analysis completed
10:04 Planning started
10:07 Planning completed
10:08 Coding worker started
10:19 Implementation completed
10:20 Build passed
10:22 Tests passed
10:24 Review found HIGH issue
10:25 Fix loop #1 started
10:31 Fix completed
10:33 Tests passed
10:35 Review approved
10:36 Waiting for human approval
```

Audit records should include:

- actor;
- stage;
- action;
- timestamp;
- input/output reference;
- tool invocation;
- provider/model;
- approval action.

---

## 21. Cost and Token Tracking

Each AI-backed stage should record:

- input tokens;
- output tokens;
- cached tokens where available;
- estimated cost;
- model;
- provider.

The run should display:

```text
Requirement:      $0.03
Planning:         $0.14
Implementation:   $0.92
Review:           $0.31
Fix:              $0.28
Documentation:    $0.05

Total:            $1.73
```

Users should eventually be able to configure:

- maximum per-run spend;
- maximum stage spend;
- maximum retry count.

A configured budget breach should stop or escalate the workflow.

---

## 22. Evaluation and Benchmark Mode

Evaluation is a major differentiator.

The same development task should be executable through two strategies.

### Strategy A — Direct Agent

```text
Developer
   ↓
Coding Agent
   ↓
Result
```

### Strategy B — DevFlowAI

```text
Developer
   ↓
Requirement Analysis
   ↓
Planning
   ↓
Coding Worker
   ↓
Build
   ↓
Tests
   ↓
Independent Review
   ↓
Fix Loop
   ↓
Result
```

### Metrics

DevFlowAI should capture:

- task success;
- build success;
- visible tests passed;
- hidden evaluation tests passed;
- defects discovered;
- regressions;
- review findings;
- human corrections;
- number of iterations;
- total tokens;
- total cost;
- execution time.

### Goal

DevFlowAI should be able to answer questions such as:

> Does orchestration improve reliability enough to justify its extra cost?

> Which task types benefit from independent review?

> Which model performs best as coder versus reviewer?

> When is direct Codex usage better than a full orchestrated workflow?

The product should **measure** these differences rather than assume orchestration is always superior.

---

## 23. Functional Requirements

### FR-1 — Repository Registration

The user can register a local Git repository.

### FR-2 — Task Submission

The user can submit a plain-language development task.

### FR-3 — SDLC Run Creation

Each submitted task creates a persistent SDLC run.

### FR-4 — Workflow Execution

DevFlowAI executes configured stages in order.

### FR-5 — Conditional Routing

Stages may be executed or skipped based on task classification and policy.

### FR-6 — Worker Invocation

DevFlowAI can invoke at least one coding worker.

### FR-7 — Repository Mutation

Authorized implementation stages may modify files inside an isolated workspace.

### FR-8 — Git Diff

DevFlowAI must expose the resulting diff.

### FR-9 — Build Execution

DevFlowAI can execute a configured build command.

### FR-10 — Test Execution

DevFlowAI can execute configured test commands.

### FR-11 — Independent Review

DevFlowAI can submit a requirement + diff to an independent reviewer.

### FR-12 — Review Findings

Review findings are persisted with severity and status.

### FR-13 — Fix Loop

Blocking findings may return the workflow to implementation.

### FR-14 — Bounded Iteration

The workflow enforces a maximum review/fix iteration count.

### FR-15 — Approval Gate

The workflow may pause before configured stages.

### FR-16 — Approval / Rejection

A user can approve or reject a paused run.

### FR-17 — Live Progress

The UI receives live workflow events through SSE or equivalent streaming.

### FR-18 — Run History

Users can inspect previous runs.

### FR-19 — Cost Tracking

AI-backed stages record estimated usage and cost.

### FR-20 — Audit Trail

Important stage, tool, worker, and approval events are persisted.

### FR-21 — Policy Configuration

Repositories can configure basic workflow requirements.

### FR-22 — PR Integration

A later release can create a GitHub pull request after approval.

### FR-23 — CI Integration

A later release can observe CI status.

### FR-24 — Evaluation Mode

A later release can compare direct-agent execution with an orchestrated run.

---

## 24. Non-Functional Requirements

### NFR-1 — Safety

Repository-changing tools must be scoped to an isolated workspace.

### NFR-2 — Reliability

Workflow state must survive application restart.

### NFR-3 — Idempotency

Resuming a workflow must avoid accidentally repeating destructive operations.

### NFR-4 — Observability

Each stage should emit structured logs and lifecycle events.

### NFR-5 — Traceability

Every run should be traceable from requirement to resulting diff.

### NFR-6 — Cost Control

Agent loops and tool execution must be bounded.

### NFR-7 — Extensibility

New stages, tools, models, and coding workers should be pluggable.

### NFR-8 — Provider Independence

Core workflow state must not depend on a provider-specific response format.

### NFR-9 — Security

Secrets and provider API keys must not be exposed to model context unless required.

### NFR-10 — Performance

The system should favor correctness and inspectability over minimizing total execution latency.

---

## 25. Suggested Technical Architecture

```text
                          DEVFLOWAI
                    SDLC CONTROL PLANE

                             │
                      Workflow Engine
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
 Requirement Agent      Planner Agent     Architecture Agent
          │                  │                  │
          └──────────────────┼──────────────────┘
                             │
                       Coding Worker
                    Codex / Claude / API
                             │
                             ↓
                       Git Workspace
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
        Build               Test              Review
          │                  │                  │
          └──────────────────┼──────────────────┘
                             │
                    Security / Quality
                             │
                             ↓
                       Human Approval
                             │
                             ↓
                       Pull Request
                             │
                             ↓
                           CI/CD
                             │
                             ↓
                        Deployment
                             │
                             ↓
                        Verification
```

Supporting components:

```text
Policy Engine
Model / Worker Router
Git Workspace Manager
Tool Execution Sandbox
Audit Service
Cost / Token Tracker
Evaluation Engine
SSE Event Stream
Persistence Layer
```

---

## 26. Recommended Technology Stack

### Backend

- Java
- Spring Boot
- Spring AI

### Persistence

MVP:

- PostgreSQL or H2 for local development

Recommended domain persistence:

- JPA / Hibernate

### Frontend

Keep the MVP small.

Options:

- simple React/Vite UI;
- server-rendered Spring UI;
- lightweight SPA.

The core engineering value is in orchestration, not frontend complexity.

### AI Integration

Spring AI should provide:

- ChatClient abstraction;
- model providers;
- structured output;
- tool calling;
- prompt templates.

### Git

Use native Git command execution initially.

Possible future abstraction:

- JGit where useful;
- native Git retained for compatibility with existing developer workflows.

### Streaming

Use:

- Server-Sent Events (SSE)

for live stage updates.

---

## 27. MVP Scope — Phase 1

The first version should prove the control-plane thesis with the smallest useful loop.

### Workflow

```text
Task
  ↓
Plan
  ↓
Implementation
  ↓
Build
  ↓
Review
  ↓
Fix if needed
  ↓
Tests
  ↓
Human Approval
  ↓
Git Diff
```

### Required features

- local repository registration;
- task submission;
- persistent SDLC run;
- planner;
- one coding worker;
- Git worktree or branch isolation;
- build execution;
- test execution;
- independent reviewer;
- bounded review → fix loop;
- human approval;
- diff inspection;
- SSE progress stream;
- token/cost tracking;
- run history.

### Explicitly excluded from Phase 1

- Jira;
- Linear;
- production deployment;
- multi-user RBAC;
- dynamic model routing;
- advanced policy DSL;
- multiple CI providers;
- large analytics dashboard.

---

## 28. Phase 2 — Full SDLC Core

Add:

- requirement analysis;
- acceptance criteria extraction;
- architecture review;
- risk classification;
- security/quality stage;
- documentation stage;
- GitHub pull request creation;
- configurable policy engine;
- CI status integration.

Resulting flow:

```text
Requirement
   ↓
Analysis
   ↓
Planning
   ↓
Architecture
   ↓
Implementation
   ↓
Build
   ↓
Tests
   ↓
Review
   ↓
Security
   ↓
Documentation
   ↓
Human Approval
   ↓
Pull Request
   ↓
CI
```

---

## 29. Phase 3 — Enterprise / Evaluation

Add:

- multiple repositories;
- Jira / GitHub Issue intake;
- model routing;
- multiple coding workers;
- policy templates;
- cost dashboards;
- workflow analytics;
- benchmark mode;
- direct-agent vs DevFlowAI comparison;
- role-based approvals;
- organization-level audit history.

---

## 30. Phase 4 — Delivery Lifecycle

Add:

- staging deployment integration;
- smoke testing;
- release approval;
- production deployment orchestration;
- post-deployment verification;
- rollback workflow;
- operational health checks.

At this stage, DevFlowAI becomes a true requirement-to-production control plane.

---

## 31. Success Metrics

### Product metrics

- percentage of SDLC runs completed successfully;
- percentage requiring human intervention;
- average review/fix iterations;
- average cost per run;
- average duration per run;
- percentage of blocking review findings resolved automatically;
- percentage of runs producing a valid build;
- percentage of runs passing all tests.

### Quality metrics

- hidden evaluation-test pass rate;
- regressions introduced;
- defects discovered during independent review;
- defects discovered only after completion;
- human corrections required.

### Evaluation metrics

Compare direct coding-agent runs against DevFlowAI:

```text
Task success rate
Build success rate
Hidden-test success
Regression count
Human intervention
Token usage
Cost
Time
```

A successful product should identify **where orchestration adds value and where it does not**.

---

## 32. Risks and Tradeoffs

### 32.1 Orchestration overhead

Multiple AI stages increase:

- latency;
- token usage;
- cost;
- implementation complexity.

Mitigation:

- conditionally skip unnecessary stages;
- use cheaper models for simple stages;
- cap loops;
- measure value using evaluation mode.

### 32.2 Context loss between agents

Separate workers can lose implementation context.

Mitigation:

- pass structured artifacts;
- allow repository inspection;
- retain stage summaries;
- avoid unnecessary agent fragmentation.

### 32.3 Coding-agent duplication

DevFlowAI may accidentally reimplement features already provided by Codex or Claude.

Mitigation:

> Keep DevFlowAI focused on workflow, policy, governance, auditability, and evaluation.

### 32.4 False confidence from AI review

An AI reviewer may approve flawed code.

Mitigation:

- deterministic tests;
- static analysis;
- security tooling;
- independent models;
- human approval;
- hidden benchmark tests.

### 32.5 Tool execution risk

Autonomous agents can execute dangerous commands.

Mitigation:

- sandboxing;
- repository-scoped workspaces;
- command allowlists;
- approval gates;
- execution timeouts.

### 32.6 Provider dependence

Coding-agent capabilities can change rapidly.

Mitigation:

- CodingWorker abstraction;
- provider-neutral workflow state;
- pluggable adapters.

---

## 33. Key Product Differentiators

DevFlowAI should not position itself as:

> "Six AI agents that write software together."

That pattern is increasingly common and easy to reproduce.

The differentiated positioning is:

### 33.1 Enforced SDLC

The workflow exists as executable software, not merely prompt instructions.

### 33.2 Agent independence

Codex, Claude, and future workers can be swapped or combined.

### 33.3 Quality gates

Builds, tests, review, security, and coverage requirements can block advancement.

### 33.4 Human governance

High-risk stages can require explicit human approval.

### 33.5 Full audit trail

The entire requirement-to-delivery lifecycle is inspectable.

### 33.6 Cost visibility

AI usage is tracked per stage and per run.

### 33.7 Evaluation

The platform tests whether orchestration actually improves outcomes.

---

## 34. Sample End-to-End Workflow

### Input

```text
Add account locking after five failed login attempts.
Reset the counter after a successful login.
Add tests for the new behavior.
```

### Requirement Analysis

```text
FR-1 Track consecutive failures.
FR-2 Lock account on fifth failed attempt.
FR-3 Reject login for locked accounts.
FR-4 Reset failures after successful login.
```

### Plan

```text
User
- failedLoginAttempts
- accountLocked

AuthenticationService
- enforce lock
- increment failure counter
- reset counter

Tests
- lock on fifth failure
- reset after success
- reject locked account
```

### Architecture

```text
PASS

Uses existing authentication service.
Database migration required.
No new dependency required.
```

### Implementation

Coding worker changes six files.

### Build

```text
PASS
```

### Tests

```text
60 / 60 PASS
```

### Review

```text
HIGH:
Locked-account check occurs after password verification.
```

### Fix Loop

Coder adjusts logic.

### Revalidation

```text
Build: PASS
Tests: 60 / 60 PASS
Review: APPROVED
Security: PASS
```

### Human Approval

```text
APPROVED
```

### Pull Request

DevFlowAI creates a PR with implementation, test, review, and run metadata.

### CI

```text
PASS
```

### Final State

```text
COMPLETED
```

---

## 35. Open Questions

These should be resolved during implementation rather than blocking the initial prototype.

1. Should Codex / Claude be invoked through CLI adapters, APIs, or both?
2. How much repository context should DevFlowAI pass versus allowing the coding worker to inspect directly?
3. Should planning and requirement analysis use separate agents in the MVP?
4. How should repository-specific conventions be represented?
5. What is the best policy configuration format: YAML, database configuration, or both?
6. When should failed CI trigger autonomous remediation versus human intervention?
7. Should evaluation runs use hidden tests stored outside the agent-visible repository?
8. How should cost be estimated consistently across providers?
9. Should review findings use a standardized schema similar to static-analysis tools?
10. At what point should a workflow require manual escalation instead of another agent iteration?

---

## 36. Recommended First Milestone

The first milestone should prove only this:

> **DevFlowAI can take one real development task, delegate implementation to a coding worker, independently validate it, enforce a bounded review/fix workflow, and return an inspectable Git diff with a complete execution record.**

Target workflow:

```text
Task
 ↓
Planner
 ↓
Coding Worker
 ↓
Build
 ↓
Reviewer
 ↓
Fix Loop
 ↓
Tests
 ↓
Human Approval
 ↓
Diff
```

If this loop is reliable, the rest of the SDLC can be layered on incrementally.

Do **not** build all 15 lifecycle stages before this core loop works.

---

## 37. Final Product Positioning

### Short description

**DevFlowAI is an AI-native SDLC control plane for orchestrating coding agents, engineering tools, quality gates, and human approvals.**

### Longer description

DevFlowAI takes software work from requirement to production through a configurable, inspectable workflow. Rather than replacing coding agents such as Codex or Claude, it coordinates them with planning, independent review, deterministic builds and tests, security checks, human approval, CI/CD, and delivery policy.

### Core thesis

> The problem is no longer simply whether AI can write code.

> The problem is how engineering organizations can safely, measurably, and consistently integrate autonomous coding agents into the complete software development lifecycle.

DevFlowAI is the platform for exploring and solving that problem.
