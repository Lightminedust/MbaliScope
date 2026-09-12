package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NeighborTableTest {
    @Test
    void parsesIpv6NeighborsFromLinuxAndWindowsStyleLines() {
        String output = """
                fe80::abcd:12 dev eth0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
                2001:db8::5     00-11-22-33-44-55  Reachable
                ff02::1         33-33-00-00-00-01  Permanent
                """;
        var neighbors = NeighborTable.parseIpv6(output);
        assertEquals("AA:BB:CC:DD:EE:FF", neighbors.get("fe80::abcd:12"));
        assertEquals("00:11:22:33:44:55", neighbors.get("2001:db8::5"));
        assertEquals(2, neighbors.size());
    }
}
