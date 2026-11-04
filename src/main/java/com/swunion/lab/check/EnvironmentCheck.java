package com.swunion.lab.check;

import com.swunion.lab.session.StudentSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

/** 점검 결과가 바뀐 시점만 남기는 이력. */
@Entity
public class EnvironmentCheck {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(optional = false)
	private StudentSession session;
	private Instant checkedAt;
	private boolean ok;
	@Column(length = 100000)
	private String issuesJson;
	@Column(length = 100000)
	private String extrasJson;

	protected EnvironmentCheck() {
	}

	public EnvironmentCheck(StudentSession session, boolean ok, String issuesJson, String extrasJson) {
		this.session = session;
		this.checkedAt = Instant.now();
		this.ok = ok;
		this.issuesJson = issuesJson;
		this.extrasJson = extrasJson;
	}

	public Long getId() { return id; }
	public StudentSession getSession() { return session; }
	public Instant getCheckedAt() { return checkedAt; }
	public boolean isOk() { return ok; }
	public String getIssuesJson() { return issuesJson; }
	public String getExtrasJson() { return extrasJson; }
}
