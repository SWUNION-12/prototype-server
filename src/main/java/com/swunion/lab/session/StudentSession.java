package com.swunion.lab.session;

import com.swunion.lab.course.Course;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

// 한 수업 안에서 이름 키는 겹치지 않는다. 이 기능 전에 만든 행은 name_key가 NULL이라 중복이 있어도 걸리지 않는다
// (H2는 고유 제약에서 NULL끼리 다르게 본다).
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "course_id", "name_key" }))
public class StudentSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(optional = false)
	private Course course;
	private String displayName;
	@Column(unique = true, nullable = false, length = 64)
	private String token;
	// 지금 토큰을 발급한 시각. 학생 쿠키(LAB_STUDENT)를 쓰기 전에 만든 행은 null이다. 그때 토큰은 주소(/lab?t=,
	// /l/<토큰>/)에 실려 나갔으므로 받아 주지 않는다(SessionService.byToken). 그 학생은 수업 코드·이름·PIN으로 다시
	// 입장하고, 되찾기가 토큰을 바꾸며 이 값을 채운다.
	private Instant tokenIssuedAt;
	private String containerId;
	private String volumeName;
	private Integer hostPort;
	private String idePassword;
	private Long startupMillis;
	private boolean containerRunning;
	private boolean ideConnected;
	private Instant lastSeenAt;
	@Enumerated(EnumType.STRING)
	private EnvStatus envStatus = EnvStatus.UNKNOWN;
	@Column(length = 100000)
	private String issuesJson = "[]";
	@Column(length = 100000)
	private String extrasJson = "[]";
	private boolean needsHelp;
	@Column(length = 4000)
	private String needsHelpReason;
	@Column(length = 4000)
	private String lastEscalatedSignature;
	private Instant createdAt;
	// 볼륨이 사라져 빈 볼륨으로 컨테이너를 다시 만든 시각. null이면 작업 공간이 이어진 것이다.
	private Instant workspaceLostAt;
	// 같은 학생인지 가르는 키(앞뒤 공백 제거 + 소문자). 이 기능 전에 만든 행은 null이고 되찾기에 성공하면 채운다.
	private String nameKey;
	// PIN의 BCrypt 해시. null이면 다음 입장이 PIN을 새로 정한다(강사가 초기화했거나 이 기능 전에 만든 세션).
	private String pinHash;
	// 기존 행이 있는 DB에 NOT NULL 열을 더하려면 기본값이 있어야 한다(ddl-auto=update).
	@ColumnDefault("0")
	private int failedPinAttempts;
	private Instant pinLockedUntil;
	// 컨테이너 안 확장이 서버에 자기를 알리는 키(무작위 32바이트의 소문자 16진 64자). 컨테이너 환경변수로 들어가며
	// PIN 되찾기로 토큰이 바뀌어도 그대로여야 하므로 한 번 정하면 바꾸지 않는다. 이 기능 전에 만든 행은 null이고
	// 고유 제약에서 NULL끼리는 다르게 보므로 걸리지 않는다. null 행에는 키를 만들어 주지 않는다.
	@Column(unique = true, length = 64)
	private String ideKey;
	// 지금 컨테이너를 만들 때의 IDE 확인 방식. 이 기능 전에 만든 행은 null이고 비밀번호 방식으로 본다(IdeAuthMode.of).
	// 멈춘 컨테이너를 다시 켜면 처음 만든 실행 인자 그대로라 값도 그대로이고, 새로 만들 때만 바뀐다.
	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private IdeAuthMode ideAuthMode;

	protected StudentSession() {
	}

	public StudentSession(Course course, String displayName, String token, String idePassword) {
		this.course = course;
		this.displayName = displayName;
		this.token = token;
		this.idePassword = idePassword;
		this.createdAt = Instant.now();
		this.tokenIssuedAt = this.createdAt;
	}

	/** PIN 입장으로 만드는 새 행. 이름 키와 PIN 해시를 처음부터 갖는다. */
	public StudentSession(Course course, String displayName, String nameKey, String token, String idePassword,
			String pinHash) {
		this(course, displayName, token, idePassword);
		this.nameKey = nameKey;
		this.pinHash = pinHash;
	}

	/** PIN 입장으로 만드는 새 행에 컨테이너 키까지 더한 것. 키는 insert 때만 정해진다. */
	public StudentSession(Course course, String displayName, String nameKey, String token, String idePassword,
			String pinHash, String ideKey) {
		this(course, displayName, nameKey, token, idePassword, pinHash);
		this.ideKey = ideKey;
	}

	public Long getId() { return id; }
	public Course getCourse() { return course; }
	public String getDisplayName() { return displayName; }
	public String getToken() { return token; }
	public Instant getTokenIssuedAt() { return tokenIssuedAt; }
	public String getContainerId() { return containerId; }
	public void setContainerId(String containerId) { this.containerId = containerId; }
	public String getVolumeName() { return volumeName; }
	public void setVolumeName(String volumeName) { this.volumeName = volumeName; }
	public Integer getHostPort() { return hostPort; }
	public void setHostPort(Integer hostPort) { this.hostPort = hostPort; }
	public String getIdePassword() { return idePassword; }
	public Long getStartupMillis() { return startupMillis; }
	public void setStartupMillis(Long startupMillis) { this.startupMillis = startupMillis; }
	public boolean isContainerRunning() { return containerRunning; }
	public void setContainerRunning(boolean containerRunning) { this.containerRunning = containerRunning; }
	public boolean isIdeConnected() { return ideConnected; }
	public void setIdeConnected(boolean ideConnected) { this.ideConnected = ideConnected; }
	public Instant getLastSeenAt() { return lastSeenAt; }
	public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
	public EnvStatus getEnvStatus() { return envStatus; }
	public void setEnvStatus(EnvStatus envStatus) { this.envStatus = envStatus; }
	public String getIssuesJson() { return issuesJson; }
	public void setIssuesJson(String issuesJson) { this.issuesJson = issuesJson; }
	public String getExtrasJson() { return extrasJson; }
	public void setExtrasJson(String extrasJson) { this.extrasJson = extrasJson; }
	public boolean isNeedsHelp() { return needsHelp; }
	public void setNeedsHelp(boolean needsHelp) { this.needsHelp = needsHelp; }
	public String getNeedsHelpReason() { return needsHelpReason; }
	public void setNeedsHelpReason(String needsHelpReason) { this.needsHelpReason = needsHelpReason; }
	public String getLastEscalatedSignature() { return lastEscalatedSignature; }
	public void setLastEscalatedSignature(String lastEscalatedSignature) { this.lastEscalatedSignature = lastEscalatedSignature; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getWorkspaceLostAt() { return workspaceLostAt; }
	// PIN 관련 값은 읽기만 한다. 쓰기는 SessionRepository의 대상 업데이트로만 한다(R13).
	public String getNameKey() { return nameKey; }
	public String getPinHash() { return pinHash; }
	public int getFailedPinAttempts() { return failedPinAttempts; }
	public Instant getPinLockedUntil() { return pinLockedUntil; }
	// 읽기만 한다. 키는 새 행을 넣을 때 정해지고 이후 어떤 쓰기도 바꾸지 않는다.
	public String getIdeKey() { return ideKey; }
	// 읽기만 한다. 쓰기는 컨테이너를 기록하는 SessionRepository.updateContainerCreated가 한다.
	public IdeAuthMode getIdeAuthMode() { return ideAuthMode; }
}
