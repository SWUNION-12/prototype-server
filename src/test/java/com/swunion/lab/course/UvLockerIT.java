package com.swunion.lab.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class UvLockerIT {

	@Test
	void locksSmallSpec(@TempDir Path d) {
		SpecFiles.write(d, 1, "3.12", List.of(new PackageSpec("six", "==", "1.16.0")));
		assertThat(new UvLocker().lock(d).ok()).isTrue();
		assertThat(d.resolve("uv.lock")).exists();
	}

	@Test
	void reportsConflict(@TempDir Path d) {
		SpecFiles.write(d, 1, "3.12",
				List.of(new PackageSpec("requests", "==", "2.32.3"), new PackageSpec("urllib3", "==", "1.0")));
		var r = new UvLocker().lock(d);
		assertThat(r.ok()).isFalse();
		assertThat(r.errorSummary()).contains("urllib3");
	}
}
