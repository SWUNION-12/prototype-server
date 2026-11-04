package com.swunion.lab.course;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Course {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String name;
	private String pythonVersion;
	@Column(length = 4000)
	private String packagesText;
	@Enumerated(EnumType.STRING)
	private CourseStatus status;
	@Column(length = 4000)
	private String errorSummary;
	private String imageName;
	@Column(length = 100000)
	private String baselineJson;
	@Column(unique = true)
	private String entryCode;
	private Instant createdAt;

	protected Course() {
	}

	public Course(String name, String pythonVersion, String packagesText) {
		this.name = name;
		this.pythonVersion = pythonVersion;
		this.packagesText = packagesText;
		this.status = CourseStatus.LOCKING;
		this.createdAt = Instant.now();
	}

	public Long getId() { return id; }
	public String getName() { return name; }
	public String getPythonVersion() { return pythonVersion; }
	public String getPackagesText() { return packagesText; }
	public CourseStatus getStatus() { return status; }
	public void setStatus(CourseStatus status) { this.status = status; }
	public String getErrorSummary() { return errorSummary; }
	public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
	public String getImageName() { return imageName; }
	public void setImageName(String imageName) { this.imageName = imageName; }
	public String getBaselineJson() { return baselineJson; }
	public void setBaselineJson(String baselineJson) { this.baselineJson = baselineJson; }
	public String getEntryCode() { return entryCode; }
	public void setEntryCode(String entryCode) { this.entryCode = entryCode; }
	public Instant getCreatedAt() { return createdAt; }
}
