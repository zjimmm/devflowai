package ai.devflow.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "sdlc_run")
public class SdlcRun {

    @Id
    private String id;

    @Column(length = 10_000, nullable = false)
    private String task;

    @Column(nullable = false)
    private String repoSlug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SdlcRunStatus status;

    @Column(length = 2_000)
    private String reason;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private long inputTokens;
    private long outputTokens;

    protected SdlcRun() {} // JPA

    public SdlcRun(String id, String task, String repoSlug, Instant startedAt) {
        this.id = id;
        this.task = task;
        this.repoSlug = repoSlug;
        this.status = SdlcRunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    public String id() { return id; }
    public String task() { return task; }
    public String repoSlug() { return repoSlug; }
    public SdlcRunStatus status() { return status; }
    public String reason() { return reason; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }

    public void finish(SdlcRunStatus status, String reason, Instant finishedAt, long inputTokens, long outputTokens) {
        this.status = status;
        this.reason = reason;
        this.finishedAt = finishedAt;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }
}
