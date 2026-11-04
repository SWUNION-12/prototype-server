package com.swunion.lab.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실제 uv 대신 받은 인자를 파일에 적는 가짜 실행 파일로, lock이 어떤 인자로 uv를 부르는지 확인한다. */
class UvLockerTest {

	@Test
	void lockNeverBuildsSourceDistributionsOnTheHost(@TempDir Path tmp) throws Exception {
		Path spec = Files.createDirectory(tmp.resolve("spec"));
		Path args = tmp.resolve("args.txt");
		Path fakeUv = tmp.resolve("fake-uv");
		Files.writeString(fakeUv, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + args + "'\n");
		Files.setPosixFilePermissions(fakeUv, PosixFilePermissions.fromString("rwx------"));

		assertThat(new UvLocker(fakeUv.toString()).lock(spec).ok()).isTrue();

		// sdist만 있는 패키지를 호스트에서 빌드하면 패키지의 빌드 코드가 서버 PC에서 실행된다.
		assertThat(Files.readAllLines(args)).containsExactly("--color", "never", "--no-progress", "lock", "--no-build");
	}
}
