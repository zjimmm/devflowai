package ai.devflow.history;

import ai.devflow.agent.Finding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_finding")
public class ReviewFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String runId;

    @Column(nullable = false)
    private int reviewIteration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Finding.Origin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Finding.Severity severity;

    private String file;
    private Integer line;

    @Column(length = 4_000, nullable = false)
    private String message;

    protected ReviewFinding() {} // JPA

    public ReviewFinding(String runId, int reviewIteration, Finding finding) {
        this.runId = runId;
        this.reviewIteration = reviewIteration;
        this.origin = finding.origin();
        this.severity = finding.severity();
        this.file = finding.file();
        this.line = finding.line();
        this.message = finding.message();
    }

    public Long id() { return id; }
    public String runId() { return runId; }
    public int reviewIteration() { return reviewIteration; }
    public Finding.Origin origin() { return origin; }
    public Finding.Severity severity() { return severity; }
    public String file() { return file; }
    public Integer line() { return line; }
    public String message() { return message; }
}
