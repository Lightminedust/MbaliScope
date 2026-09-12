package org.mbali.service;

import java.util.Locale;
import java.util.List;
import java.util.TreeSet;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ports TCP en écoute sur la machine locale, avec le PID du processus qui les détient. */
final class ListeningPorts {

    record Listener(String address, int port, int pid) {}

    /**
     * "  TCP    0.0.0.0:445   0.0.0.0:0   LISTENING   4"
     * Le motif de l'adresse locale accepte aussi la forme IPv6 "[::]:445".
     */
    private static final Pattern LINE = Pattern.compile(
            "^\\s*TCP\\s+(\\S+):(\\d{1,5})\\s+\\S+\\s+(\\S+)\\s+(\\d+)\\s*$");
    private static final Pattern SS_LINE = Pattern.compile(
            "^\\s*LISTEN\\s+\\S+\\s+\\S+\\s+(.+):(\\d{1,5})\\s+\\S+(?:.*pid=(\\d+).*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LSOF_LINE = Pattern.compile(
            "^\\S+\\s+(\\d+)\\s+.*\\sTCP\\s+(.+):(\\d{1,5})\\s+\\(LISTEN\\)\\s*$", Pattern.CASE_INSENSITIVE);

    private ListeningPorts() {
    }

    static List<Listener> read() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return ShellCommand.output("netstat", "-ano").map(ListeningPorts::parse).orElseGet(List::of);
        }
        if (os.contains("mac")) {
            return ShellCommand.output("lsof", "-nP", "-iTCP", "-sTCP:LISTEN")
                    .map(ListeningPorts::parseLsof).orElseGet(List::of);
        }
        return ShellCommand.output("ss", "-H", "-lntp")
                .map(ListeningPorts::parseSs)
                .or(() -> ShellCommand.output("netstat", "-lntp").map(ListeningPorts::parseSs))
                .orElseGet(List::of);
    }

    /** Écoutes distinctes par adresse, port et PID, triées pour un affichage stable. */
    static List<Listener> parse(String output) {
        var ports = new TreeSet<>(Comparator.comparingInt(Listener::port)
                .thenComparing(Listener::address).thenComparingInt(Listener::pid));
        for (String line : output.split("\\R")) {
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            // L'état reste en anglais sur la plupart des installations, mais on ne
            // compare que le préfixe pour survivre à une variante localisée.
            if (!matcher.group(3).toUpperCase(Locale.ROOT).startsWith("LISTEN")) {
                continue;
            }
            // Seuls les doublons exacts sont éliminés.
            ports.add(new Listener(matcher.group(1), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(4))));
        }
        return List.copyOf(ports);
    }

    static List<Listener> parseSs(String output) {
        return parseWith(output, SS_LINE, 1, 2, 3);
    }

    static List<Listener> parseLsof(String output) {
        return parseWith(output, LSOF_LINE, 2, 3, 1);
    }

    private static List<Listener> parseWith(String output, Pattern pattern,
                                             int addressGroup, int portGroup, int pidGroup) {
        var listeners = new TreeSet<>(Comparator.comparingInt(Listener::port)
                .thenComparing(Listener::address).thenComparingInt(Listener::pid));
        for (String line : output.split("\\R")) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String pid = matcher.group(pidGroup);
                listeners.add(new Listener(matcher.group(addressGroup),
                        Integer.parseInt(matcher.group(portGroup)), pid == null ? 0 : Integer.parseInt(pid)));
            }
        }
        return List.copyOf(listeners);
    }
}
