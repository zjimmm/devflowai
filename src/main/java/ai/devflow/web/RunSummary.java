package ai.devflow.web;

import ai.devflow.history.SdlcRun;

import java.time.Instant;

public record RunSummary(String runId, String task, String repoSlug, String status, String reason,
                          Instant startedAt, Instant finishedAt, long inputTokens, long outputTokens,
                          String strategy, Boolean buildSucceeded, String prUrl, String ciStatus,
                          String releaseStatus, String releaseUrl) {

    public static RunSummary from(SdlcRun run) {
        return new RunSummary(run.id(), run.task(), run.repoSlug(), run.status().name(), run.reason(),
                run.startedAt(), run.finishedAt(), run.inputTokens(), run.outputTokens(),
                run.strategy() == null ? null : run.strategy().name(), run.buildSucceeded(), run.prUrl(), run.ciStatus(),
                run.releaseStatus(), run.releaseUrl());
    }
}
