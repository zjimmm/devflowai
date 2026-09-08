package ai.devflow.tools;

/** A safe, concise view of one remote CI check run. */
public record CiCheck(String name, String status, String conclusion, String detailsUrl) {}
