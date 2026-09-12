package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ServiceProbeTest {

    @Test
    void verifiesHttpFromTheActualResponseInsteadOfThePortNumber() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread responder = Thread.ofVirtual().start(() -> {
                try (var client = server.accept()) {
                    client.getInputStream().readNBytes(4);
                    client.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            ServiceProbe.Fingerprint result = ServiceProbe.identify(
                    "127.0.0.1", server.getLocalPort(), 1_000);
            responder.join();
            assertEquals("HTTP", result.label());
            assertTrue(result.verified());
        }
    }

    @Test
    void keepsAnUnverifiedHintWhenNoSignatureIsAvailable() {
        var result = ServiceProbe.hint(8080);
        assertEquals("TCP 8080", result.label());
        assertTrue(result.evidence().contains("HTTP"));
    }
}
