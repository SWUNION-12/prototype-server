package com.swunion.lab.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class InstallScriptRendererTest {

	private static final String TOKEN = "ab12".repeat(16);
	private final InstallScriptRenderer r = new InstallScriptRenderer("http://192.168.0.5:8080");

	@Test
	void installShIsFullyRenderedAndHasRequiredSteps() {
		String s = r.installSh(TOKEN, 7);
		assertThat(s).doesNotContain("{{");
		assertThat(s).contains("uv sync --frozen", "uv run --frozen python verify.py", "SWUNION_DIR",
				"http://192.168.0.5:8080", TOKEN, "swunion-course-", "command -v uv",
				"curl -LsSf https://astral.sh/uv/install.sh | sh", "$HOME/.local/bin",
				"failed=install:$1", "fail_block UvSyncFailed", "---SWUNION-VERIFY-BEGIN---");
		assertThat(s).contains("7");
	}

	@Test
	void installPs1IsFullyRenderedAndHasRequiredSteps() {
		String s = r.installPs1(TOKEN, 7);
		assertThat(s).doesNotContain("{{");
		assertThat(s).contains("irm https://astral.sh/uv/install.ps1", "uv sync --frozen",
				"uv run --frozen python verify.py", "SWUNION_DIR", "http://192.168.0.5:8080", TOKEN,
				"USERPROFILE\\.local\\bin", "failed=install:$Reason", "Write-FailBlock 'UvSyncFailed'");
		assertThat(s).doesNotContain("AMD64");
	}

	@Test
	void commands() {
		assertThat(r.installCommand("macos", TOKEN))
				.isEqualTo("curl -fsSL \"http://192.168.0.5:8080/l/" + TOKEN + "/install.sh\" | sh");
		assertThat(r.installCommand("windows", TOKEN)).isEqualTo(
				"powershell -ExecutionPolicy ByPass -c \"irm http://192.168.0.5:8080/l/" + TOKEN + "/install.ps1 | iex\"");
		String win = r.diagnoseCommand("windows");
		assertThat(win).contains("PROCESSOR_ARCHITECTURE", "x86_64", "arm64", "os=windows");
		assertThat(win).doesNotContain("\n");
		assertThat(r.diagnoseCommand("macos")).doesNotContain("\n");
		assertThatThrownBy(() -> r.diagnoseCommand("linux")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsUnsafeValues() {
		assertThatThrownBy(() -> r.installSh("abc\"; rm -rf ~; \"", 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InstallScriptRenderer("http://x\"; rm -rf ~; \"").installSh(TOKEN, 1))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void macDiagnoseCommandOutputPassesParser() throws Exception {
		Process p = new ProcessBuilder("sh", "-c", r.diagnoseCommand("macos")).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
		Diagnostics d = DiagnosticsParser.parse(out, "macos");
		assertThat(d.os()).isEqualTo("macos");
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void verifyPyCompilesAndEmbedsExpectations() throws Exception {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("scikit-learn", "1.5.2");
		expected.put("six", null);
		String py = r.verifyPy(expected);
		assertThat(py).contains("\"scikit-learn\": \"1.5.2\"", "\"six\": null", "---SWUNION-VERIFY-BEGIN---");
		Path dir = Files.createTempDirectory("verifypy");
		Path f = dir.resolve("verify.py");
		Files.writeString(f, py);
		Process p = new ProcessBuilder("python3", "-I", "-m", "py_compile", f.toString())
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
		assertThat(p.exitValue()).as(out).isZero();
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void verifyPyRunsAndReportsMissingPackage() throws Exception {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("definitely-not-installed-pkg", "1.0");
		Path dir = Files.createTempDirectory("verifypy");
		Path f = dir.resolve("verify.py");
		Files.writeString(f, r.verifyPy(expected));
		Process p = new ProcessBuilder("python3", "-I", f.toString()).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
		VerifyResult res = VerifyOutputParser.parse(out);
		assertThat(res.ok()).isFalse();
		assertThat(res.failed()).containsExactly("definitely-not-installed-pkg:NotInstalled");
		assertThat(res.total()).isEqualTo(1);
		assertThat(res.passed()).isZero();
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void verifyPyPassesForInstalledStdlibLikePackage() throws Exception {
		// pip는 파이썬 배포판에 거의 항상 있고 버전을 모르므로, 설치된 버전을 읽어 기대값으로 쓴다.
		Process v = new ProcessBuilder("python3", "-I", "-c",
				"import importlib.metadata as m;print(m.version('pip'))").redirectErrorStream(true).start();
		String ver = new String(v.getInputStream().readAllBytes()).trim();
		v.waitFor(30, TimeUnit.SECONDS);
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("pip", ver);
		Path f = Files.createTempDirectory("verifypy").resolve("verify.py");
		Files.writeString(f, r.verifyPy(expected));
		Process p = new ProcessBuilder("python3", "-I", f.toString()).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		p.waitFor(30, TimeUnit.SECONDS);
		VerifyResult res = VerifyOutputParser.parse(out);
		assertThat(res.ok()).as(out).isTrue();
		assertThat(res.passed()).isEqualTo(1);
	}
}
