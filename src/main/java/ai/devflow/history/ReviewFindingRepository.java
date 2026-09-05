package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewFindingRepository extends JpaRepository<ReviewFinding, Long> {
    List<ReviewFinding> findByRunId(String runId);
}
