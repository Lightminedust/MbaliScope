package org.mbali.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Identification prudente d'un service ouvert à partir de sa réponse réseau. */
final class ServiceProbe {

    record Fingerprint(String label, String evidence, boolean verified) {
    }

    private ServiceProbe() {
    }

    static Fingerprint identify(String host, int port, int timeoutMs) {
        try {
            return port == 443 || port == 8443
                    ? probeTls(host, port, timeoutMs)
                    : probePlain(host, port, timeoutMs);
        } catch (IOException | GeneralSecurityException e) {
            return hint(port);
        }
    }

    private static Fingerprint probePlain(String host, int port, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(("HEAD / HTTP/1.0\r\nHost: " + host + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String line = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            if (line == null) {
                return hint(port);
            }
            line = sanitize(line);
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("HTTP/")) return new Fingerprint("HTTP", line, true);
            if (upper.startsWith("SSH-")) return new Fingerprint("SSH", line, true);
            if (upper.startsWith("220") && upper.contains("SMTP")) return new Fingerprint("SMTP", line, true);
            if (upper.startsWith("+OK")) return new Fingerprint("POP3", line, true);
            return new Fingerprint("Service TCP", line, false);
        }
    }

    private static Fingerprint probeTls(String host, int port, int timeoutMs)
            throws IOException, GeneralSecurityException {
        TrustManager[] permissive = {new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, permissive, new SecureRandom());
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
            return new Fingerprint("TLS", socket.getSession().getProtocol() + " · "
                    + socket.getSession().getCipherSuite(), true);
        }
    }

    static Fingerprint hint(int port) {
        String likely = NetworkScanner.serviceHint(port);
        String evidence = likely == null ? "Aucune signature reçue" : "Port souvent utilisé par " + likely;
        return new Fingerprint("TCP " + port, evidence, false);
    }

    private static String sanitize(String line) {
        String printable = line.replaceAll("[^\\p{Print}]", " ").trim();
        return printable.length() <= 300 ? printable : printable.substring(0, 300);
    }
}
