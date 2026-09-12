package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.mbali.service.PortScanner.PortStatus;

class PortScannerTest {
    @Test
    void detectsARealListeningSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            assertEquals(PortStatus.OUVERT,
                    PortScanner.scanPort("127.0.0.1", server.getLocalPort(), 500));
        }
    }

    @Test
    void rejectsInvalidArgumentsBeforeOpeningASocket() {
        assertThrows(IllegalArgumentException.class,
                () -> PortScanner.scanPort("127.0.0.1", 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> PortScanner.scanPort("", 80, 100));
    }
}
