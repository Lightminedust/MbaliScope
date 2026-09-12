package org.mbali.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Voisins IPv4 et IPv6 effectivement connus du système d'exploitation. */
final class NeighborTable {

    private static final Pattern MAC = Pattern.compile("(?i)([0-9a-f]{2}(?:[:-][0-9a-f]{2}){5})");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?:[0-9a-f]{0,4}:){2,}[0-9a-f]{0,4}(?:%[\\w.-]+)?");

    private NeighborTable() {
    }

    static Map<String, String> read() {
        Map<String, String> result = new LinkedHashMap<>(ArpTable.read());
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            ShellCommand.output("netsh", "interface", "ipv6", "show", "neighbors")
                    .map(NeighborTable::parseIpv6).ifPresent(result::putAll);
        } else if (os.contains("mac")) {
            ShellCommand.output("ndp", "-an").map(NeighborTable::parseIpv6).ifPresent(result::putAll);
        } else {
            ShellCommand.output("ip", "-6", "neigh", "show")
                    .map(NeighborTable::parseIpv6).ifPresent(result::putAll);
        }
        return result;
    }

    static Map<String, String> parseIpv6(String output) {
        Map<String, String> neighbors = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher address = IPV6.matcher(line);
            Matcher mac = MAC.matcher(line);
            if (address.find() && mac.find()) {
                String normalized = mac.group(1).replace('-', ':').toUpperCase(Locale.ROOT);
                if ((Integer.parseInt(normalized.substring(0, 2), 16) & 1) == 0) {
                    neighbors.putIfAbsent(address.group(), normalized);
                }
            }
        }
        return neighbors;
    }
}
