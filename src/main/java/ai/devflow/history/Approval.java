package ai.devflow.history;

import ai.devflow.orchestrator.Gate;
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
@Table(name = "approval")
public class Approval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gate gate;

    @Column(nullable = false)
    private boolean approved;

    @Column(length = 2_000)
    private String reason;

    @Column(nullable = false)
    private Instant decidedAt;

    protected Approval() {} // JPA

    public Approval(String runId, Gate gate, boolean approved, String reason, Instant decidedAt) {
        this.runId = runId;
        this.gate = gate;
        this.approved = approved;
        this.reason = reason;
        this.decidedAt = decidedAt;
    }

    public Long id() { return id; }
    public String runId() { return runId; }
    public Gate gate() { return gate; }
    public boolean approved() { return approved; }
    public String reason() { return reason; }
    public Instant decidedAt() { return decidedAt; }
}
