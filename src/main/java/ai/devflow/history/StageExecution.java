package ai.devflow.history;

import ai.devflow.orchestrator.RunPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "stage_execution")
public class StageExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunPhase phase;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    protected StageExecution() {} // JPA

    public StageExecution(String runId, RunPhase phase, Instant startedAt) {
        this.runId = runId;
        this.phase = phase;
        this.startedAt = startedAt;
    }

    public Long id() { return id; }
    public String runId() { return runId; }
    public RunPhase phase() { return phase; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }

    public void close(Instant endedAt) { this.endedAt = endedAt; }
}
