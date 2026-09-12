package org.mbali.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * Passerelle par défaut, lue dans la table de routage du système.
 * Plus fiable que de supposer qu'il s'agit de la première adresse du sous-réseau :
 * beaucoup de box opérateur se placent en fin de plage.
 */
final class RoutingTable {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private RoutingTable() {
    }

    static Optional<String> defaultGateway() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return ShellCommand.output("ip", "route", "show", "default")
                    .flatMap(RoutingTable::parseDefaultGateway)
                    .or(() -> ShellCommand.output("route", "-n", "get", "default")
                            .flatMap(RoutingTable::parseDefaultGateway))
                    .or(() -> ShellCommand.output("netstat", "-rn")
                            .flatMap(RoutingTable::parseDefaultGateway));
        }
        return ShellCommand.output("netstat", "-rn")
                .flatMap(RoutingTable::parseDefaultGateway)
                .or(() -> ShellCommand.output("ip", "route").flatMap(RoutingTable::parseDefaultGateway));
    }

    /**
     * Reconnaît les écritures de la route par défaut selon les systèmes :
     * Windows "0.0.0.0 0.0.0.0 <passerelle> ...", Linux "0.0.0.0 <passerelle> ..."
     * ou "default via <passerelle> ...", macOS "default <passerelle> ...".
     */
    static Optional<String> parseDefaultGateway(String output) {
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith("0.0.0.0") && !line.startsWith("default")) {
                continue;
            }
            Matcher matcher = IPV4.matcher(line);
            while (matcher.find()) {
                String candidate = matcher.group();
                // La première IP réelle de la ligne est la passerelle : les 0.0.0.0
                // qui précèdent sont la destination et le masque.
                if (!candidate.equals("0.0.0.0") && !candidate.equals("255.255.255.255")) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
