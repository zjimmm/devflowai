package ai.devflow.agent;

public record TokenUsage(long input, long output) {
    public static final TokenUsage NONE = new TokenUsage(0, 0);
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input(), output + other.output());
    }
}
