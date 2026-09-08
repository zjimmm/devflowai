package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunAuditEntryRepository extends JpaRepository<RunAuditEntry, Long> {
    List<RunAuditEntry> findByRunIdOrderByOccurredAtAscIdAsc(String runId);
}
