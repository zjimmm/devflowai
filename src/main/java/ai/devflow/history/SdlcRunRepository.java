package ai.devflow.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SdlcRunRepository extends JpaRepository<SdlcRun, String> {
    List<SdlcRun> findTop50ByOrderByStartedAtDesc();
}
