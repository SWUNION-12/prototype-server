package com.swunion.lab.session;

import com.swunion.lab.docker.ContainerSpec;
import com.swunion.lab.docker.DockerGateway;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 학생 컨테이너를 다시 돌게 만드는 유일한 곳. 되찾기, 수업 재개, 학생 개별 재시작이 함께 쓴다.
 * 수업 상태는 보지 않는다(호출자가 정한다). IDE가 응답할 때까지는 기다리지 않는다(호출자가 정한다).
 * Docker 오류는 던지지 않고 FAILED로 돌려준다. 한 학생이 실패해도 호출자가 나머지를 이어 가게 하려는 것이다.
 */
@Component
public class ContainerEnsurer {

	private static final Logger log = LoggerFactory.getLogger(ContainerEnsurer.class);
	static final String WORKSPACE_LOST_REASON = "작업 공간 유실";

	public enum EnsureOutcome {
		ALREADY_RUNNING, STARTED, RECREATED, RECREATED_EMPTY, FAILED
	}

	/** @param hostPort 돌고 있는 컨테이너의 호스트 포트. FAILED면 null. */
	public record EnsureResult(EnsureOutcome outcome, Integer hostPort) {
	}

	private static final EnsureResult FAILED = new EnsureResult(EnsureOutcome.FAILED, null);

	private final SessionRepository sessions;
	private final DockerGateway docker;
	private final ContainerSpecs specs;
	private final Clock clock;

	public ContainerEnsurer(SessionRepository sessions, DockerGateway docker, ContainerSpecs specs, Clock clock) {
		this.sessions = sessions;
		this.docker = docker;
		this.specs = specs;
		this.clock = clock;
	}

	/** 돌고 있으면 그대로 두고, 멈춰 있으면 켜고, Docker에서 사라졌으면 세션의 볼륨으로 다시 만든다. */
	public EnsureResult ensureRunning(StudentSession s) {
		return ensure(s, false);
	}

	/** ensureRunning과 같되 돌고 있는 컨테이너도 다시 시작한다. 돌지만 IDE가 응답하지 않는 경우를 위한 것이다. */
	public EnsureResult restart(StudentSession s) {
		return ensure(s, true);
	}

	private EnsureResult ensure(StudentSession s, boolean restartIfRunning) {
		long sid = s.getId();
		String containerId = blankToNull(s.getContainerId());
		String volumeName = blankToNull(s.getVolumeName());
		try {
			if (containerId != null) {
				if (!restartIfRunning && docker.isRunning(containerId)) {
					return new EnsureResult(EnsureOutcome.ALREADY_RUNNING, s.getHostPort());
				}
				if (docker.containerExists(containerId)) {
					return restartExisting(sid, containerId);
				}
			}
			return recreate(s, volumeName);
		}
		catch (RuntimeException e) {
			// docker-java는 DockerOperationException이 아닌 자체 예외도 던지므로 RuntimeException 전체를 받는다.
			log.error("세션 {} 컨테이너를 돌게 하지 못했습니다.", sid, e);
			return FAILED;
		}
	}

	// 재시작하면 호스트 포트가 바뀔 수 있어 다시 읽어 저장한다. 기다리는 동안 점검·학생 호출이 다른 컬럼을
	// 바꿀 수 있으므로 포트와 실행 여부만 쓴다.
	private EnsureResult restartExisting(long sid, String containerId) {
		docker.restart(containerId);
		int port = docker.hostPort(containerId, ContainerSpecs.IDE_PORT);
		sessions.updateContainerState(sid, port, true);
		return new EnsureResult(EnsureOutcome.STARTED, port);
	}

	private EnsureResult recreate(StudentSession s, String volumeName) {
		long sid = s.getId();
		if (volumeName == null) {
			// 컨테이너가 사라졌는데 어느 볼륨을 붙일지 모른다. 새 빈 볼륨을 임의로 만들면 학생 코드를 놓친다.
			// 컨테이너 기록도 볼륨 이름도 없는 행(입장이 실패해 남은 것)도 여기서 Docker를 건드리지 않고 끝난다.
			log.warn("세션 {}: 볼륨 이름이 없어 컨테이너를 다시 만들 수 없습니다.", sid);
			return FAILED;
		}
		// createAndStart가 없는 볼륨을 빈 채로 만들어 버리므로 만들기 전에 확인한다. 만든 뒤에는 볼륨이 있다고 나온다.
		boolean workspaceLost = !docker.volumeExists(volumeName);
		if (workspaceLost) {
			// 유실은 볼륨에 대한 사실이라 컨테이너를 만들었는지와 무관하다. 만들다 실패해도(빈 볼륨만 남을 수 있다)
			// 다음 시도는 볼륨이 있다고 보므로, 모르게 되기 전에 지금 기록한다.
			recordWorkspaceLoss(sid, volumeName);
		}
		// 옛 컨테이너는 이미 없으므로 같은 이름을 다시 쓴다. 이름이 아직 충돌하면 createAndStart가 던지고
		// 아래가 FAILED로 돌려준다. 이름으로 지우는 일은 하지 않는다.
		ContainerSpec spec = specs.forSession(s, s.getCourse());
		// 시작 단계에서 실패하면 게이트웨이가 자기 컨테이너를 지우고 던지므로 여기서 치울 컨테이너가 없다.
		String newId = docker.createAndStart(spec);
		try {
			int port = docker.hostPort(newId, ContainerSpecs.IDE_PORT);
			if (sessions.updateContainerCreated(sid, newId, port) == 0) {
				// 만드는 사이에 세션이 지워졌다. 기록할 곳이 없는 컨테이너를 남기지 않는다.
				removeOwnContainer(sid, newId);
				log.warn("세션 {}: 컨테이너를 만드는 사이 세션이 지워졌습니다.", sid);
				return FAILED;
			}
			return new EnsureResult(workspaceLost ? EnsureOutcome.RECREATED_EMPTY : EnsureOutcome.RECREATED, port);
		}
		catch (RuntimeException e) {
			// 기록되지 않은 컨테이너가 같은 이름으로 돌고 있으면 다음 시도가 이름 충돌로 계속 실패한다.
			removeOwnContainer(sid, newId);
			throw e;
		}
	}

	// 처음 기록할 때만 강사 도움 필요 이유를 덧붙인다. 같은 유실이 다시 확인돼도 강사가 치운 표시를 되살리지 않는다.
	private void recordWorkspaceLoss(long sid, String volumeName) {
		if (sessions.markWorkspaceLost(sid, clock.instant()) > 0) {
			log.warn("세션 {}: 볼륨 {}이(가) 없습니다. 빈 작업 공간으로 컨테이너를 만듭니다.", sid, volumeName);
			sessions.addNeedsHelpReason(sid, WORKSPACE_LOST_REASON);
		}
	}

	/** 이번 호출이 만든 컨테이너를 id로만 지운다. 볼륨은 학생 코드일 수 있어 건드리지 않는다. */
	private void removeOwnContainer(long sid, String containerId) {
		try {
			docker.remove(containerId);
		}
		catch (RuntimeException e) {
			log.warn("세션 {} 방금 만든 컨테이너 정리 실패: {}", sid, e.getMessage());
		}
	}

	private static String blankToNull(String v) {
		return v == null || v.isBlank() ? null : v;
	}
}
