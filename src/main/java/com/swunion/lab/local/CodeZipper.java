package com.swunion.lab.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** docker cp가 준 tar(맨 앞 폴더 project/)를 학생이 받을 zip으로 바꾼다. */
public final class CodeZipper {

	private static final Set<String> EXCLUDED_DIRS = Set.of(".venv", "__pycache__", ".ipynb_checkpoints",
			".pytest_cache", ".mypy_cache");

	private CodeZipper() {
	}

	public static void tarToZip(InputStream tar, OutputStream zip, String rootFolder) throws IOException {
		ZipOutputStream out = new ZipOutputStream(zip);
		TarArchiveInputStream in = new TarArchiveInputStream(tar);
		for (TarArchiveEntry e; (e = in.getNextEntry()) != null;) {
			// 심볼릭 링크와 장치 파일은 담지 않는다. 폴더는 파일 경로로 자연히 만들어진다.
			if (!e.isFile()) {
				continue;
			}
			String rel = relativePath(e.getName());
			if (rel == null || excluded(rel)) {
				continue;
			}
			out.putNextEntry(new ZipEntry(rootFolder + "/" + rel));
			in.transferTo(out);
			out.closeEntry();
		}
		out.finish();
		out.flush();
	}

	/** 앞의 project/ 를 떼고, 경로를 벗어나는 이름(zip slip)은 null로 버린다. */
	private static String relativePath(String name) {
		String n = name.replace('\\', '/');
		while (n.startsWith("./")) {
			n = n.substring(2);
		}
		if (n.startsWith("project/")) {
			n = n.substring("project/".length());
		}
		if (n.isEmpty() || n.startsWith("/")) {
			return null;
		}
		for (String part : n.split("/")) {
			if (part.equals("..")) {
				return null;
			}
		}
		return n;
	}

	private static boolean excluded(String rel) {
		String[] parts = rel.split("/");
		for (String p : parts) {
			if (EXCLUDED_DIRS.contains(p)) {
				return true;
			}
		}
		return parts[parts.length - 1].endsWith(".pyc");
	}
}
