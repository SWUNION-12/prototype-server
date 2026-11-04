package com.swunion.lab.check;

import com.swunion.lab.session.StudentSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentCheckRepository extends JpaRepository<EnvironmentCheck, Long> {

	Optional<EnvironmentCheck> findFirstBySessionOrderByIdDesc(StudentSession session);

	List<EnvironmentCheck> findBySessionOrderByIdAsc(StudentSession session);
}
