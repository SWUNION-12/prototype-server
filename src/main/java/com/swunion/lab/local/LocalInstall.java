package com.swunion.lab.local;

import com.swunion.lab.session.StudentSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import java.time.Instant;

/** 세션당 한 행. 조회 결과를 받으면 DIAGNOSED, 검증 결과를 받으면 OK 또는 FAIL로 갱신한다. */
@Entity
public class LocalInstall {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@OneToOne(optional = false)
	@JoinColumn(unique = true)
	private StudentSession session;
	private String os;
	private String arch;
	private String pythonVersion;
	@Enumerated(EnumType.STRING)
	private LocalStatus status;
	@Column(length = 4000)
	private String failedJson;
	private Instant updatedAt;

	protected LocalInstall() {
	}

	public LocalInstall(StudentSession session) {
		this.session = session;
		this.updatedAt = Instant.now();
	}

	public Long getId() { return id; }
	public StudentSession getSession() { return session; }
	public String getOs() { return os; }
	public void setOs(String os) { this.os = os; }
	public String getArch() { return arch; }
	public void setArch(String arch) { this.arch = arch; }
	public String getPythonVersion() { return pythonVersion; }
	public void setPythonVersion(String pythonVersion) { this.pythonVersion = pythonVersion; }
	public LocalStatus getStatus() { return status; }
	public void setStatus(LocalStatus status) { this.status = status; }
	public String getFailedJson() { return failedJson; }
	public void setFailedJson(String failedJson) { this.failedJson = failedJson; }
	public Instant getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
