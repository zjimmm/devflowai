package ai.devflow.web;

import ai.devflow.history.SdlcRun;

import java.time.Instant;

public record RunSummary(String runId, String task, String repoSlug, String status, String reason,
                          Instant startedAt, Instant finishedAt, long inputTokens, long outputTokens,
                          String strategy, String requirementStatus, Integer acceptanceCriteriaCount,
                          Boolean buildSucceeded, String prUrl, String ciStatus,
                          String stagingStatus, String stagingUrl, String stagingVerificationStatus,
                          String smokeStatus, String smokeUrl,
                          String releaseStatus, String releaseUrl, String verificationStatus,
                          String healthStatus, String healthUrl, String rollbackStatus, String rollbackUrl) {

    public static RunSummary from(SdlcRun run) {
        return new RunSummary(run.id(), run.task(), run.repoSlug(), run.status().name(), run.reason(),
                run.startedAt(), run.finishedAt(), run.inputTokens(), run.outputTokens(),
                run.strategy() == null ? null : run.strategy().name(), run.requirementStatus(),
                run.acceptanceCriteriaCount(), run.buildSucceeded(), run.prUrl(), run.ciStatus(),
                run.stagingStatus(), run.stagingUrl(), run.stagingVerificationStatus(),
                run.smokeStatus(), run.smokeUrl(),
                run.releaseStatus(), run.releaseUrl(), run.verificationStatus(),
                run.healthStatus(), run.healthUrl(), run.rollbackStatus(), run.rollbackUrl());
    }
}
