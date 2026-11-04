package com.swunion.lab.check;

public final class TcpConnections {

	private TcpConnections() {
	}

	/** /proc/net/tcp(6) 내용에서 로컬 포트가 port이고 상태가 ESTABLISHED(01)인 줄 수. */
	public static int countEstablished(String procNetTcp, int port) {
		if (procNetTcp == null) {
			return 0;
		}
		String hexPort = String.format("%04X", port);
		int count = 0;
		for (String line : procNetTcp.split("\n")) {
			String[] f = line.trim().split("\\s+");
			// 0: "sl:" 1: local_address 2: rem_address 3: st
			if (f.length < 4 || !f[0].endsWith(":")) {
				continue;
			}
			int colon = f[1].lastIndexOf(':');
			if (colon < 0) {
				continue;
			}
			if (f[1].substring(colon + 1).equalsIgnoreCase(hexPort) && f[3].equals("01")) {
				count++;
			}
		}
		return count;
	}
}
