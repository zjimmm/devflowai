package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StageExecutionRepository extends JpaRepository<StageExecution, Long> {
    Optional<StageExecution> findByRunIdAndEndedAtIsNull(String runId);
}
