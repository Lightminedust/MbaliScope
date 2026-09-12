package org.mbali.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Sonde TCP élémentaire. L'orchestration du réseau appartient à {@link NetworkScanner}. */
public final class PortScanner {

    public enum PortStatus { OUVERT, FERME, FILTRE }

    private PortScanner() {
    }

    public static PortStatus scanPort(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("L'adresse ne peut pas être vide");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port hors plage : " + port);
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("Le délai doit être positif");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return PortStatus.OUVERT;
        } catch (SocketTimeoutException | NoRouteToHostException e) {
            return PortStatus.FILTRE;
        } catch (ConnectException e) {
            return PortStatus.FERME;
        } catch (IOException e) {
            return PortStatus.FILTRE;
        }
    }
}
