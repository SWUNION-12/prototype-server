package com.swunion.lab.agent;

import com.swunion.lab.session.StudentSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

/** 에이전트 실행 한 번의 기록(AGT-7). 실행 중에는 저장하지 않고 재점검까지 끝난 뒤 한 번에 저장한다. */
@Entity
public class AgentAction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(optional = false)
	private StudentSession session;
	// TRIGGER는 SQL 키워드라 컬럼 이름을 바꿔 둔다.
	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type")
	private Trigger trigger;
	@Enumerated(EnumType.STRING)
	private Classification classification;
	@Column(length = 100000)
	private String toolLogJson;
	@Column(length = 100000)
	private String rejectedJson;
	private boolean recheckOk;
	@Column(length = 100000)
	private String recheckIssuesJson;
	@Enumerated(EnumType.STRING)
	private Outcome outcome;
	@Column(length = 2000)
	private String summary;
	/** 학생에게 보낼 설명(코드 블록 제거 후). 도움 요청 결과에 쓴다. */
	@Column(length = 4000)
	private String explanation;
	/** ESCALATED일 때 강사에게 보인 이유. */
	@Column(length = 4000)
	private String escalationReason;
	private Instant startedAt;
	private Instant finishedAt;

	protected AgentAction() {
	}

	AgentAction(StudentSession session, Trigger trigger, Instant startedAt) {
		this.session = session;
		this.trigger = trigger;
		this.startedAt = startedAt;
	}

	public Long getId() { return id; }
	public StudentSession getSession() { return session; }
	public Trigger getTrigger() { return trigger; }
	public Classification getClassification() { return classification; }
	void setClassification(Classification classification) { this.classification = classification; }
	public String getToolLogJson() { return toolLogJson; }
	void setToolLogJson(String toolLogJson) { this.toolLogJson = toolLogJson; }
	public String getRejectedJson() { return rejectedJson; }
	void setRejectedJson(String rejectedJson) { this.rejectedJson = rejectedJson; }
	public boolean isRecheckOk() { return recheckOk; }
	void setRecheckOk(boolean recheckOk) { this.recheckOk = recheckOk; }
	public String getRecheckIssuesJson() { return recheckIssuesJson; }
	void setRecheckIssuesJson(String recheckIssuesJson) { this.recheckIssuesJson = recheckIssuesJson; }
	public Outcome getOutcome() { return outcome; }
	void setOutcome(Outcome outcome) { this.outcome = outcome; }
	public String getSummary() { return summary; }
	void setSummary(String summary) { this.summary = summary; }
	public String getExplanation() { return explanation; }
	void setExplanation(String explanation) { this.explanation = explanation; }
	public String getEscalationReason() { return escalationReason; }
	void setEscalationReason(String escalationReason) { this.escalationReason = escalationReason; }
	public Instant getStartedAt() { return startedAt; }
	public Instant getFinishedAt() { return finishedAt; }
	void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
