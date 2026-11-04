package com.swunion.lab.local;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalInstallRepository extends JpaRepository<LocalInstall, Long> {

	Optional<LocalInstall> findBySessionId(Long sessionId);

	/** 강사 대시보드가 수업의 로컬 설치 상태를 한 번에 읽는다. */
	List<LocalInstall> findBySessionCourseId(Long courseId);
}
