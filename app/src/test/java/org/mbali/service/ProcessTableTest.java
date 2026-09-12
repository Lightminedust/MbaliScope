package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ProcessTableTest {

    @Test
    void parsesPosixProcessListAndTrimsExecutablePaths() {
        var processes = ProcessTable.parsePosix("""
                   1 /sbin/init
                 912 /usr/bin/java
                """);
        assertEquals("init", processes.get(1));
        assertEquals("java", processes.get(912));
    }

    @Test
    void readsTheTasklistFormat() {
        String output = """
                "System Idle Process","0","Services","0","8 K"
                "System","4","Services","0","8 808 K"
                "Code.exe","18096","Console","6","692 128 K"
                """;

        Map<Integer, String> processes = ProcessTable.parseTasklist(output);

        assertEquals(3, processes.size());
        assertEquals("System", processes.get(4));
        assertEquals("Code", processes.get(18096), "l'extension .exe est retirée");
    }

    @Test
    void readsThePowerShellFallbackFormat() {
        String output = """
                "Id","ProcessName"
                "4","System"
                "18096","Code"
                "12240","sqlservr"
                """;

        Map<Integer, String> processes = ProcessTable.parsePowerShell(output);

        assertEquals(3, processes.size());
        assertEquals("System", processes.get(4));
        assertEquals("sqlservr", processes.get(12240));
    }

    @Test
    void emptyOutputYieldsNoProcess() {
        assertEquals(Map.of(), ProcessTable.parseTasklist(""));
        assertEquals(Map.of(), ProcessTable.parsePowerShell(""));
    }
}
