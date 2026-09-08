package ai.devflow.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "run_audit_entry")
public class RunAuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    private String phase;

    @Column(length = 10_000, nullable = false)
    private String message;

    @Column(length = 8_000, nullable = false)
    private String dataJson;

    @Column(nullable = false)
    private Instant occurredAt;

    protected RunAuditEntry() {}

    public RunAuditEntry(String runId, String actor, String action, String phase, String message,
                         String dataJson, Instant occurredAt) {
        this.runId = runId;
        this.actor = actor;
        this.action = action;
        this.phase = phase;
        this.message = message;
        this.dataJson = dataJson;
        this.occurredAt = occurredAt;
    }

    public Long id() { return id; }
    public String runId() { return runId; }
    public String actor() { return actor; }
    public String action() { return action; }
    public String phase() { return phase; }
    public String message() { return message; }
    public String dataJson() { return dataJson; }
    public Instant occurredAt() { return occurredAt; }
}
