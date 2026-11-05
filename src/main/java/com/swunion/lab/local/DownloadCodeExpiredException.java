package com.swunion.lab.local;

/** 다운로드 코드를 모르거나, 10분이 지났거나, 가리키던 세션이 재입장·삭제로 사라졌다. 컨트롤러가 404로 바꾼다. */
public class DownloadCodeExpiredException extends RuntimeException {

	public DownloadCodeExpiredException() {
		super("설치 링크가 만료되었습니다. 코딩책상 화면에서 설치 명령을 다시 받아 주세요.");
	}
}
