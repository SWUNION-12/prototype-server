package com.swunion.lab.session;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<StudentSession, Long> {

	Optional<StudentSession> findByToken(String token);
}
