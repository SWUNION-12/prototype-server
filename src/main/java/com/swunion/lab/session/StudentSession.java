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
import java.time.Instant;

@Entity
public class StudentSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(optional = false)
	private Course course;
	private String displayName;
	@Column(unique = true, nullable = false, length = 64)
	private String token;
	private String containerId;
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

	protected StudentSession() {
	}

	public StudentSession(Course course, String displayName, String token, String idePassword) {
		this.course = course;
		this.displayName = displayName;
		this.token = token;
		this.idePassword = idePassword;
		this.createdAt = Instant.now();
	}

	public Long getId() { return id; }
	public Course getCourse() { return course; }
	public String getDisplayName() { return displayName; }
	public String getToken() { return token; }
	public String getContainerId() { return containerId; }
	public void setContainerId(String containerId) { this.containerId = containerId; }
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
}
