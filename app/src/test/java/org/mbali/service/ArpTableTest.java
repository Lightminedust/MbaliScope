package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ArpTableTest {

    // Sortie réelle de "arp -a" sur la machine de développement Windows
    private static final String WINDOWS_OUTPUT = """
            Interface: 192.168.1.64 --- 0x16
              Internet Address      Physical Address      Type
              192.168.1.68          02-11-22-33-44-55     dynamic
              192.168.1.254         00-11-22-33-44-66     dynamic
              192.168.1.255         ff-ff-ff-ff-ff-ff     static
              224.0.0.22            01-00-5e-00-00-16     static
            """;

    private static final String LINUX_OUTPUT = """
            ? (192.168.1.254) at 00:11:22:33:44:66 [ether] on wlan0
            ? (192.168.1.68) at 02:11:22:33:44:55 [ether] on wlan0
            """;

    @Test
    void readsWindowsNeighbours() {
        Map<String, String> neighbours = ArpTable.parse(WINDOWS_OUTPUT);

        assertEquals(2, neighbours.size());
        assertEquals("02:11:22:33:44:55", neighbours.get("192.168.1.68"));
        assertEquals("00:11:22:33:44:66", neighbours.get("192.168.1.254"));
    }

    @Test
    void ignoresBroadcastAndMulticastEntries() {
        Map<String, String> neighbours = ArpTable.parse(WINDOWS_OUTPUT);

        assertFalse(neighbours.containsKey("192.168.1.255"), "la diffusion n'est pas un appareil");
        assertFalse(neighbours.containsKey("224.0.0.22"), "la multidiffusion n'est pas un appareil");
    }

    @Test
    void doesNotPairTheHeaderAddressWithTheFirstMac() {
        // 192.168.1.64 apparaît dans l'en-tête "Interface:", sans MAC à lui
        assertFalse(ArpTable.parse(WINDOWS_OUTPUT).containsKey("192.168.1.64"));
    }

    @Test
    void readsLinuxNeighbours() {
        Map<String, String> neighbours = ArpTable.parse(LINUX_OUTPUT);

        assertEquals(2, neighbours.size());
        assertEquals("00:11:22:33:44:66", neighbours.get("192.168.1.254"));
        assertEquals("02:11:22:33:44:55", neighbours.get("192.168.1.68"));
    }

    @Test
    void emptyOutputYieldsNoNeighbour() {
        assertEquals(Map.of(), ArpTable.parse(""));
    }
}
