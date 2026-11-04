package com.swunion.lab.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class CodeZipperTest {

	@Test
	void stripsProjectPrefixAndExcludesJunk() throws Exception {
		byte[] tar = tar("project/", "project/hello.py", "project/.venv/x", "project/a/__pycache__/b.pyc",
				"project/c.pyc", "project/.ipynb_checkpoints/n", "project/data/d.csv", "project/.pytest_cache/p",
				"project/.mypy_cache/m", "project/src/.venv", "project/../evil.txt");
		ByteArrayOutputStream zip = new ByteArrayOutputStream();
		CodeZipper.tarToZip(new ByteArrayInputStream(tar), zip, "course-1");
		assertThat(names(zip.toByteArray())).containsExactly("course-1/hello.py", "course-1/data/d.csv");
	}

	@Test
	void keepsContent() throws Exception {
		byte[] tar = tar("project/hello.py");
		ByteArrayOutputStream zip = new ByteArrayOutputStream();
		CodeZipper.tarToZip(new ByteArrayInputStream(tar), zip, "course-7");
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.toByteArray()))) {
			in.getNextEntry();
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("content of project/hello.py");
		}
	}

	private static byte[] tar(String... paths) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (TarArchiveOutputStream t = new TarArchiveOutputStream(out)) {
			for (String p : paths) {
				if (p.endsWith("/")) {
					TarArchiveEntry d = new TarArchiveEntry(p);
					t.putArchiveEntry(d);
					t.closeArchiveEntry();
					continue;
				}
				byte[] data = ("content of " + p).getBytes(StandardCharsets.UTF_8);
				TarArchiveEntry e = new TarArchiveEntry(p);
				e.setSize(data.length);
				t.putArchiveEntry(e);
				t.write(data);
				t.closeArchiveEntry();
			}
		}
		return out.toByteArray();
	}

	private static List<String> names(byte[] zip) throws Exception {
		List<String> names = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null;) {
				names.add(e.getName());
			}
		}
		return names;
	}
}
