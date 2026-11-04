package com.swunion.lab.agent;

import java.util.ArrayList;
import java.util.List;

/** 한 실행 동안 도구가 쌓는 상태. 실행 하나는 스레드 하나에서만 돌기 때문에 동기화하지 않는다. */
public class RunState {

	/** "도구 인자 => 결과" 한 줄씩. AgentAction.toolLogJson으로 저장한다. */
	public final List<String> toolLog = new ArrayList<>();
	/** 실행하지 않은 호출. "도구이름: 이유". */
	public final List<String> rejected = new ArrayList<>();
	/** 코드 블록을 지운 학생용 설명. */
	public String explanation;
	public String escalationReason;
	public Classification classification;
	public String summary;
	public boolean finished;
	/** restore_packages 또는 restart_ide를 실제로 실행했는지. RESOLVED 판단에 쓴다. */
	public boolean repairAttempted;
	public int restoreAttempts;
	public int restartAttempts;
	public int llmCalls;
	public boolean llmFailed;
}
