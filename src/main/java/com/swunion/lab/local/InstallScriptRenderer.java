package com.swunion.lab.local;

import com.swunion.lab.config.LabProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 스크립트와 명령어를 만든다. 템플릿에 넣는 값은 서버가 정한 것(설정의 기준 주소, 16진 다운로드 코드, 숫자 수업 id)뿐이고,
 * 학생이 붙여넣은 값은 어디에도 들어가지 않는다. 그래도 값이 쉘 따옴표를 깨지 못하게 한 번 더 검사한다.
 * 주소에는 세션 토큰이 아니라 10분짜리 다운로드 코드(DownloadCodes)를 싣는다.
 */
@Component
public class InstallScriptRenderer {

	private static final Pattern SAFE_BASE = Pattern.compile("^https?://[A-Za-z0-9.\\-:\\[\\]]+(/[A-Za-z0-9._~\\-/]*)?$");
	private static final Pattern SAFE_CODE = Pattern.compile("^[0-9a-f]{64}$");
	// PEP 440의 로컬 버전(+)과 epoch(!)를 허용한다. 둘 다 verify.py의 r'''...''' 안 JSON에서 무해하다.
	private static final Pattern SAFE_VERSION = Pattern.compile("^[A-Za-z0-9._+!-]+$");
	private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]+$");

	private final String baseUrl;

	@Autowired
	public InstallScriptRenderer(LabProperties props) {
		this(props.publicBaseUrl());
	}

	InstallScriptRenderer(String baseUrl) {
		this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
	}

	public String baseUrl() {
		return safeBase();
	}

	/** 학생 터미널에 붙여넣는 한 줄. 경로·사용자 이름·컴퓨터 이름은 출력하지 않는다. */
	public String diagnoseCommand(String os) {
		return switch (os) {
			// /usr/bin/python3는 개발자 도구가 없으면 설치 창을 띄우는 껍데기라서, 도구가 있을 때만 부른다.
			case "macos" -> "echo os=macos; echo arch=$(uname -m); v=none; p=$(command -v python3); "
					+ "if [ -n \"$p\" ] && { [ \"$p\" != /usr/bin/python3 ] || xcode-select -p >/dev/null 2>&1; }; then "
					+ "v=$(\"$p\" -c 'import sys;print(*sys.version_info[:3],sep=chr(46))' 2>/dev/null); fi; "
					+ "echo python=${v:-none}";
			// Microsoft Store 별칭은 stdout에 버전 모양을 내지 않으므로 정규식에 걸리지 않아 none이 된다.
			// 지원하지 않는 CPU(x86 등)는 바꾸지 않고 그대로 출력해 서버 검사가 거부하게 한다.
			case "windows" -> "\"os=windows\"; $a=$env:PROCESSOR_ARCHITEW6432; if(-not $a){$a=$env:PROCESSOR_ARCHITECTURE}; "
					+ "if($a -eq 'AMD64'){$a='x86_64'}elseif($a -eq 'ARM64'){$a='arm64'}; \"arch=$a\"; $v='none'; "
					+ "try{$o=\"$(& python -c 'import sys;print(*sys.version_info[:3],sep=chr(46))' 2>$null)\"; "
					+ "if($o -match '^\\d+\\.\\d+\\.\\d+$'){$v=$o}}catch{}; \"python=$v\"";
			default -> throw new IllegalArgumentException("지원하지 않는 OS: " + os);
		};
	}

	public String installCommand(String os, String code) {
		checkCode(code);
		String base = safeBase();
		return switch (os) {
			case "macos" -> "curl -fsSL \"" + base + "/l/" + code + "/install.sh\" | sh";
			case "windows" -> "powershell -ExecutionPolicy ByPass -c \"irm " + base + "/l/" + code
					+ "/install.ps1 | iex\"";
			default -> throw new IllegalArgumentException("지원하지 않는 OS: " + os);
		};
	}

	public String scriptUrl(String os, String code) {
		checkCode(code);
		return safeBase() + "/l/" + code + (os.equals("windows") ? "/install.ps1" : "/install.sh");
	}

	public String zipUrl(String code) {
		checkCode(code);
		return safeBase() + "/l/" + code + "/code.zip";
	}

	public String installSh(String code, long courseId) {
		return fill("install.sh.tmpl", code, courseId);
	}

	public String installPs1(String code, long courseId) {
		return fill("install.ps1.tmpl", code, courseId);
	}

	/**
	 * @param expectedDirect 직접 지정한 패키지의 정규화한 이름 -> 기준 버전. 기준에 없으면 값이 null이고,
	 *                       이때는 버전 비교 없이 import만 확인한다.
	 */
	public String verifyPy(Map<String, String> expectedDirect) {
		StringBuilder json = new StringBuilder("{");
		boolean first = true;
		for (var e : expectedDirect.entrySet()) {
			String name = e.getKey();
			String version = e.getValue();
			if (!SAFE_ID.matcher(name).matches() || (version != null && !SAFE_VERSION.matcher(version).matches())) {
				throw new IllegalArgumentException("verify.py에 넣을 수 없는 값입니다.");
			}
			json.append(first ? "" : ", ").append('"').append(name).append("\": ")
					.append(version == null ? "null" : "\"" + version + "\"");
			first = false;
		}
		json.append('}');
		return load("verify.py.tmpl").replace("{{EXPECTED_JSON}}", json);
	}

	private String fill(String template, String code, long courseId) {
		checkCode(code);
		String s = load(template).replace("{{BASE_URL}}", safeBase()).replace("{{CODE}}", code)
				.replace("{{COURSE_ID}}", Long.toString(courseId));
		if (s.contains("{{")) {
			throw new IllegalStateException("채워지지 않은 자리표시자가 남았습니다: " + template);
		}
		return s;
	}

	private String safeBase() {
		if (!SAFE_BASE.matcher(baseUrl).matches()) {
			throw new IllegalStateException("lab.public-base-url 형식이 올바르지 않습니다.");
		}
		return baseUrl;
	}

	private static void checkCode(String code) {
		if (code == null || !SAFE_CODE.matcher(code).matches()) {
			throw new IllegalArgumentException("다운로드 코드 형식이 올바르지 않습니다.");
		}
	}

	private static String load(String name) {
		try (var in = new ClassPathResource("local/" + name).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
