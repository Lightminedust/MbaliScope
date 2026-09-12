package org.mbali.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Donne un nom aux appareils du réseau, et dit honnêtement quand il n'y en a pas.
 *
 * Les sources ont été mesurées sur un réseau domestique réel, et elles sont très
 * inégales :
 *   — le DNS inverse répond pour la box (« dsldevice.lan ») et se tait pour le reste ;
 *   — SSDP/UPnP livre un nom convivial, un constructeur et un modèle, mais seuls
 *     les équipements qui l'implémentent répondent ;
 *   — NetBIOS, LLMNR et mDNS n'ont donné aucune réponse.
 *
 * Quand aucune source n'aboutit, il faut le dire au lieu de laisser croire à un
 * défaut : un téléphone récent tire une adresse MAC aléatoire et ne répond à aucun
 * protocole de nommage, précisément pour ne pas être identifié.
 */
public final class NameResolver {

    /** Ce que l'on a pu établir sur un appareil. */
    public record Identity(String name, String evidence) {
    }

    private static final String CRLF = "" + (char) 13 + (char) 10;
    private static final String QUOTE = "" + (char) 34;
    private static final int SSDP_TIMEOUT_MS = 1_200;
    private static final int SSDP_WINDOW_MS = 2_500;
    private static final int HTTP_TIMEOUT_MS = 2_000;

    /** Une description UPnP tient dans quelques kilooctets ; au-delà, on coupe. */
    private static final int MAX_DESCRIPTION_BYTES = 128 * 1024;

    private NameResolver() {
    }

    /**
     * Le second chiffre hexadécimal du premier octet porte le bit « administrée
     * localement ». Quand il est levé, l'adresse est tirée au hasard par l'appareil :
     * aucun constructeur ne peut en être déduit, et elle changera.
     */
    public static boolean isRandomMac(String mac) {
        if (mac == null || mac.length() < 2) {
            return false;
        }
        try {
            return (Integer.parseInt(mac.substring(0, 2), 16) & 0x02) != 0;
        } catch (NumberFormatException notHexadecimal) {
            return false;
        }
    }

    /**
     * Une adresse MAC n'est exploitable que si elle désigne vraiment une carte.
     *
     * La table de voisinage renvoie parfois une adresse nulle : c'est un remplissage
     * quand le système n'a pas encore résolu le voisin, pas un matériel. La prendre
     * au sérieux produisait un « Appareil 00:00:00 », et surtout faisait fusionner en
     * un seul nœud tous les appareils partageant ce même remplissage.
     */
    public static boolean isUsableMac(String mac) {
        if (mac == null || mac.isBlank() || mac.length() < 17) {
            return false;
        }
        boolean anyNonZero = false;
        for (int i = 0; i < mac.length(); i++) {
            char c = mac.charAt(i);
            if (c == ':' || c == '-') {
                continue;
            }
            if (Character.digit(c, 16) < 0) {
                return false;
            }
            if (c != '0') {
                anyNonZero = true;
            }
        }
        return anyNonZero;
    }

    /** Les trois premiers octets identifient le constructeur, si l'adresse est universelle. */
    public static String vendorPrefix(String mac) {
        if (!isUsableMac(mac) || isRandomMac(mac)) {
            return null;
        }
        return mac.substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /**
     * Nom d'hôte par DNS inverse, ou null quand la résolution ne donne rien.
     *
     * Faute d'enregistrement PTR, getCanonicalHostName rend l'adresse elle-même —
     * mais réécrite : « fe80::1234 » revient sous la forme développée
     * « fe80:0:0:0:0:0:0:1234 ». Une comparaison de chaînes laisse donc
     * passer ce faux nom. On vérifie que le résultat n'est pas, une fois réanalysé,
     * la même adresse que celle de départ.
     */
    public static String reverseDns(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            String name = address.getCanonicalHostName();
            if (name == null || name.isBlank() || name.equalsIgnoreCase(ip)) {
                return null;
            }
            if (isSameAddress(name, address)) {
                return null;
            }
            return name;
        } catch (IOException | RuntimeException unresolved) {
            return null;
        }
    }

    /** Vrai si le texte n'est qu'une autre écriture de la même adresse numérique. */
    private static boolean isSameAddress(String candidate, InetAddress address) {
        try {
            return InetAddress.getByName(candidate).equals(address);
        } catch (IOException | RuntimeException notAnAddress) {
            // Un vrai nom d'hôte ne se réanalyse pas en cette adresse : c'est bon signe.
            return false;
        }
    }

    /**
     * Une seule passe SSDP pour tout le réseau : on collecte les URL de description
     * annoncées, puis on lit le nom convivial dans chaque document.
     */
    public static Map<String, Identity> discoverUpnp() {
        return discoverUpnp(() -> false);
    }

    /** Même balayage, interruptible : il dure plusieurs secondes. */
    public static Map<String, Identity> discoverUpnp(BooleanSupplier cancelled) {
        Map<String, String> locations = new LinkedHashMap<>();
        String search = "M-SEARCH * HTTP/1.1" + CRLF
                + "HOST: 239.255.255.250:1900" + CRLF
                + "MAN: " + QUOTE + "ssdp:discover" + QUOTE + CRLF
                + "MX: 2" + CRLF
                + "ST: upnp:rootdevice" + CRLF + CRLF;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SSDP_TIMEOUT_MS);
            byte[] payload = search.getBytes();
            socket.send(new DatagramPacket(payload, payload.length,
                    new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)));

            byte[] buffer = new byte[2048];
            long deadline = System.currentTimeMillis() + SSDP_WINDOW_MS;
            while (System.currentTimeMillis() < deadline && !cancelled.getAsBoolean()) {
                try {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    String location = header(new String(buffer, 0, reply.getLength()), "LOCATION:");
                    if (!location.isEmpty()) {
                        locations.putIfAbsent(reply.getAddress().getHostAddress(), location);
                    }
                } catch (IOException noMoreReplies) {
                    // On laisse la fenêtre s'écouler : d'autres équipements répondent plus tard.
                }
            }
        } catch (IOException unavailable) {
            return Map.of();
        }

        Map<String, Identity> identities = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : locations.entrySet()) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            String ip = entry.getKey();
            String document = fetch(entry.getValue(), ip);
            if (document == null) {
                continue;
            }
            String friendly = between(document, "<friendlyName>", "</friendlyName>");
            String manufacturer = between(document, "<manufacturer>", "</manufacturer>");
            String model = between(document, "<modelName>", "</modelName>");
            if (friendly.isEmpty() && manufacturer.isEmpty()) {
                continue;
            }
            String name = friendly.isEmpty() ? manufacturer : friendly;
            StringBuilder evidence = new StringBuilder("UPnP");
            if (!manufacturer.isEmpty()) {
                evidence.append(" · ").append(manufacturer);
            }
            if (!model.isEmpty()) {
                evidence.append(" · ").append(model);
            }
            identities.put(ip, new Identity(name, evidence.toString()));
        }
        return identities;
    }

    /**
     * Compose le meilleur nom disponible, dans l'ordre de fiabilité décroissante, et
     * explique l'absence quand il n'y a rien à dire.
     */
    public static Identity resolve(String ip, String mac, boolean gateway,
                                   Map<String, Identity> upnp) {
        Identity announced = upnp == null ? null : upnp.get(ip);
        if (announced != null) {
            return announced;
        }
        String hostname = reverseDns(ip);
        if (hostname != null) {
            return new Identity(hostname, "DNS inverse");
        }
        if (gateway) {
            return new Identity("Passerelle", "Route par défaut");
        }
        if (isUsableMac(mac) && isRandomMac(mac)) {
            // Dire pourquoi le nom manque vaut mieux qu'un libellé qui semble cassé.
            return new Identity("Appareil · MAC aléatoire", "Adresse MAC privée, aucun nom annoncé");
        }
        String vendor = vendorPrefix(mac);
        if (vendor != null) {
            // 00:11:22 est un préfixe OUI, pas un nom de constructeur : l'appeler
            // « constructeur » laissait croire qu'on avait identifié la marque, alors
            // qu'on n'a que les trois octets qui lui sont attribués.
            return new Identity("Appareil " + vendor,
                    "Préfixe OUI " + vendor + " · marque non résolue");
        }
        return new Identity("Appareil " + suffix(ip), "Aucun nom annoncé");
    }

    private static String suffix(String address) {
        int separator = Math.max(address.lastIndexOf('.'), address.lastIndexOf(':'));
        return separator < 0 ? address : address.substring(separator + 1);
    }

    /**
     * N'ouvre que ce que l'appareil ayant répondu peut légitimement servir.
     *
     * L'URL vient d'un en-tête envoyé par une machine du réseau, que l'on ne contrôle
     * pas. Sans contrôle, elle pouvait nous faire émettre une requête vers n'importe
     * quelle cible — un autre hôte, un autre protocole — et une réponse sans fin
     * saturait la mémoire. On exige donc du HTTP, sur l'adresse même de l'émetteur,
     * sans suivre les redirections, et on lit une quantité bornée.
     *
     * Une URL désignant l'appareil par un nom plutôt que par son adresse est rejetée :
     * la résoudre supposerait une interrogation DNS pilotée par l'appareil.
     */
    static boolean allowedDescriptionUrl(String location, String announcedBy) {
        try {
            URI uri = URI.create(location);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            String host = uri.getHost();
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            int scope = host.indexOf('%');
            if (scope > 0) {
                host = host.substring(0, scope);
            }
            return host.equalsIgnoreCase(announcedBy);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static String fetch(String location, String announcedBy) {
        if (!allowedDescriptionUrl(location, announcedBy)) {
            return null;
        }
        try {
            URLConnection connection = URI.create(location).toURL().openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            if (connection instanceof HttpURLConnection http) {
                // Une redirection contournerait le contrôle d'hôte fait juste au-dessus.
                http.setInstanceFollowRedirects(false);
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readNBytes(MAX_DESCRIPTION_BYTES), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException unreachable) {
            return null;
        }
    }

    static String header(String response, String prefix) {
        for (String line : response.split(CRLF)) {
            if (line.toUpperCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf(close, start + open.length());
        return end < 0 ? "" : text.substring(start + open.length(), end).trim();
    }
}
