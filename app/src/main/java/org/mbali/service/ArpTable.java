package org.mbali.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voisins connus du système, lus dans la table ARP.
 * Ces machines ont répondu au niveau liaison, ce qui prouve leur existence même
 * lorsqu'elles ne laissent aucun port TCP ouvert — le cas de la plupart des box.
 */
final class ArpTable {

    /**
     * Une IPv4 puis une adresse MAC sur la même ligne. Le séparateur exclut les
     * chiffres et les fins de ligne, ce qui évite d'apparier une IP d'en-tête avec
     * la MAC d'une ligne suivante. Windows écrit "aa-bb-cc", Linux et macOS "aa:bb:cc".
     */
    private static final Pattern ENTRY = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{1,3}){3})[^\\r\\n\\d]+?([0-9a-fA-F]{2}(?:[:-][0-9a-fA-F]{2}){5})");

    private ArpTable() {
    }

    static Map<String, String> read() {
        return ShellCommand.output("arp", "-a").map(ArpTable::parse).orElseGet(Map::of);
    }

    /** Adresse IP vers adresse MAC, normalisée en majuscules séparées par ':'. */
    static Map<String, String> parse(String output) {
        Map<String, String> neighbours = new LinkedHashMap<>();
        Matcher matcher = ENTRY.matcher(output);
        while (matcher.find()) {
            String mac = matcher.group(2).replace('-', ':').toUpperCase(Locale.ROOT);
            if (isGroupAddress(mac)) {
                continue;
            }
            neighbours.putIfAbsent(matcher.group(1), mac);
        }
        return neighbours;
    }

    /**
     * Écarte diffusion et multidiffusion : le bit de poids faible du premier octet
     * vaut 1 pour une adresse de groupe (ff:ff:... pour la diffusion, 01:00:5e:...
     * et 33:33:... pour la multidiffusion), jamais pour une vraie carte réseau.
     */
    private static boolean isGroupAddress(String mac) {
        return (Integer.parseInt(mac.substring(0, 2), 16) & 1) == 1;
    }
}
