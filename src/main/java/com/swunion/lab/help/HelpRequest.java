package com.swunion.lab.help;

import com.swunion.lab.session.StudentSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

@Entity
public class HelpRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	private StudentSession session;
	private String errorType;
	@Column(length = 4000)
	private String errorText;
	@Enumerated(EnumType.STRING)
	private HelpStatus status = HelpStatus.PROCESSING;
	@Column(length = 4000)
	private String explanation;
	private boolean escalated;
	private Instant createdAt = Instant.now();

	protected HelpRequest() {
	}

	public HelpRequest(StudentSession session, String errorType, String errorText) {
		this.session = session;
		this.errorType = errorType;
		this.errorText = errorText;
	}

	public Long getId() { return id; }
	public StudentSession getSession() { return session; }
	public String getErrorType() { return errorType; }
	public String getErrorText() { return errorText; }
	public HelpStatus getStatus() { return status; }
	public String getExplanation() { return explanation; }
	public boolean isEscalated() { return escalated; }
	public Instant getCreatedAt() { return createdAt; }
}
