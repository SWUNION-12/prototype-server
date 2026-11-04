package com.swunion.lab.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TcpConnectionsTest {

	static final String HEADER = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";

	@Test
	void countsOnlyEstablishedOnPort() {
		String t = HEADER
				+ "   0: 0100007F:1F90 0100007F:D2A4 01 00000000:00000000 00:00000000 00000000  1000        0 111 1\n"
				+ "   1: 0100007F:1F90 0200007F:D2A5 01 00000000:00000000 00:00000000 00000000  1000        0 112 1\n"
				+ "   2: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 113 1\n"
				+ "   3: 0100007F:0050 0100007F:D2A6 01 00000000:00000000 00:00000000 00000000  1000        0 114 1\n";
		assertThat(TcpConnections.countEstablished(t, 8080)).isEqualTo(2);
	}

	@Test
	void emptyAndTcp6() {
		assertThat(TcpConnections.countEstablished("", 8080)).isZero();
		assertThat(TcpConnections.countEstablished(null, 8080)).isZero();
		String t6 = "   0: 00000000000000000000000001000000:1F90 00000000000000000000000001000000:9C40 01 0:0 00:0 0 0 0 1\n";
		assertThat(TcpConnections.countEstablished(t6, 8080)).isEqualTo(1);
	}
}
