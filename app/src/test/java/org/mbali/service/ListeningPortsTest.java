package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.mbali.service.ListeningPorts.Listener;

import org.junit.jupiter.api.Test;

class ListeningPortsTest {

    // Extrait réel de "netstat -ano" sur la machine de développement
    private static final String OUTPUT = """
            Active Connections

              Proto  Local Address          Foreign Address        State           PID
              TCP    0.0.0.0:80             0.0.0.0:0              LISTENING       4
              TCP    0.0.0.0:445            0.0.0.0:0              LISTENING       4
              TCP    127.0.0.1:1434         0.0.0.0:0              LISTENING       12240
              TCP    [::]:445               [::]:0                 LISTENING       4
              TCP    192.168.1.64:52100     203.0.113.10:443       ESTABLISHED     18096
              UDP    0.0.0.0:5353           *:*                                    2100
            """;

    @Test
    void keepsOnlyListeningTcpPortsWithTheirPid() {
        List<Listener> ports = ListeningPorts.parse(OUTPUT);

        assertEquals(4, ports.size());
        assertTrue(ports.contains(new Listener("0.0.0.0", 80, 4)));
        assertTrue(ports.contains(new Listener("[::]", 445, 4)));
        assertTrue(ports.contains(new Listener("127.0.0.1", 1434, 12240)));
    }

    @Test
    void ignoresEstablishedConnectionsAndUdp() {
        List<Listener> ports = ListeningPorts.parse(OUTPUT);

        assertFalse(ports.stream().anyMatch(p -> p.port() == 52100), "une connexion sortante n'est pas un port en écoute");
        assertFalse(ports.stream().anyMatch(p -> p.port() == 5353), "seul le TCP est analysé pour l'instant");
    }

    @Test
    void portsAreSortedForAStableDisplay() {
        assertEquals(List.of(80, 445, 445, 1434), ListeningPorts.parse(OUTPUT).stream().map(Listener::port).toList());
    }

    @Test
    void preservesDifferentOwnersOnTheSamePortAndRemovesExactDuplicates() {
        var listeners = ListeningPorts.parse("""
                TCP 127.0.0.1:8080 0.0.0.0:0 LISTENING 100
                TCP 192.168.1.2:8080 0.0.0.0:0 LISTENING 200
                TCP 127.0.0.1:8080 0.0.0.0:0 LISTENING 100
                """);
        assertEquals(List.of(new Listener("127.0.0.1", 8080, 100),
                new Listener("192.168.1.2", 8080, 200)), listeners);
    }

    @Test
    void parsesLinuxSsOutputIncludingIpv6AndUnknownOwners() {
        String output = """
                LISTEN 0 4096 127.0.0.1:631 0.0.0.0:* users:(("cupsd",pid=712,fd=7))
                LISTEN 0 128 [::]:22 [::]:* users:(("sshd",pid=42,fd=3))
                LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*
                """;
        assertEquals(List.of(
                new Listener("[::]", 22, 42),
                new Listener("127.0.0.1", 631, 712),
                new Listener("0.0.0.0", 8080, 0)), ListeningPorts.parseSs(output));
    }

    @Test
    void parsesMacLsofOutput() {
        String output = "java 918 me 34u IPv6 0xabc 0t0 TCP [::1]:9000 (LISTEN)";
        assertEquals(List.of(new Listener("[::1]", 9000, 918)), ListeningPorts.parseLsof(output));
    }

    @Test
    void emptyOutputYieldsNoPort() {
        assertTrue(ListeningPorts.parse("").isEmpty());
    }
}
