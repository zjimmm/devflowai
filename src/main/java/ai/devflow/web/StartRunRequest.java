package ai.devflow.web;

/** @param repo "fixture" today; Phase 6 accepts a git URL here. */
public record StartRunRequest(String task, String repo) {}
