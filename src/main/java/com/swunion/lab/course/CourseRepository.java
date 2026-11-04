package com.swunion.lab.course;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CourseRepository extends JpaRepository<Course, Long> {

	Optional<Course> findByEntryCode(String entryCode);

	boolean existsByEntryCode(String entryCode);

	List<Course> findByStatusIn(Collection<CourseStatus> statuses);

	List<Course> findAllByOrderByIdDesc();

	/**
	 * 영속성 컨텍스트에 남은 엔티티가 아니라 DB의 현재 상태를 읽는다. 수업 종료와 겹친 입장·재시작이
	 * 요청 초반에 읽어 둔 READY를 믿으면 종료된 수업에 컨테이너가 살아난다.
	 */
	@Query("select c.status from Course c where c.id = :id")
	Optional<CourseStatus> findStatusById(@Param("id") long id);

	/**
	 * 상태가 from일 때만 to로 바꾼다. 수업 종료가 동시에 진행 중인 발급이나 다른 종료 요청을 덮어쓰지 않게
	 * 조건과 갱신을 한 문장으로 한다.
	 * @return 갱신된 행 수(조건이 안 맞으면 0)
	 */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Course c set c.status = :to where c.id = :id and c.status = :from")
	int updateStatusIf(@Param("id") long id, @Param("from") CourseStatus from, @Param("to") CourseStatus to);
}
