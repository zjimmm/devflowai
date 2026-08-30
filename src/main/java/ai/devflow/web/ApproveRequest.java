package ai.devflow.web;

/** @param reason optional; a rejection carrying one steers instead of aborting. */
public record ApproveRequest(boolean approved, String reason) {}
