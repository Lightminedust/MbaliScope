package org.mbali.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Les appareils Bluetooth appairés à cette machine, et leur état réel.
 *
 * Windows expose un même appareil sous plusieurs entrées. Mesuré sur une vraie machine :
 * cinq pour une paire d'écouteurs — l'appareil lui-même, puis un nœud par service
 * (réception audio, télécommande AVRCP deux fois, mains-libres). Toutes partagent
 * l'adresse radio, qui sert donc de clé de regroupement, plutôt qu'un nom qui varie.
 *
 * « Présent » ne veut pas dire « connecté ». Ces mêmes écouteurs étaient déclarés
 * présents et en état OK alors que leur dernière connexion datait de quatre mois. La
 * propriété {83DA6326-…} 15 est la seule qui dise la vérité : c'est elle qui est lue.
 */
final class BluetoothInventory {

    /** Ce qu'est un appareil, déduit de ses services, puis à défaut de sa classe. */
    enum Kind {
        AUDIO("Casque ou écouteurs"),
        INPUT("Clavier, souris ou manette"),
        PHONE("Téléphone"),
        COMPUTER("Ordinateur"),
        WEARABLE("Objet porté"),
        OTHER("Appareil Bluetooth");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() { return label; }
    }

    /** Un appareil appairé. connected vaut null quand Windows ne dit rien de son état. */
    record BluetoothDevice(String address, String name, Kind kind, Boolean connected,
                           LocalDate lastConnected, boolean lowEnergy) {

        String formattedAddress() {
            StringBuilder mac = new StringBuilder();
            for (int i = 0; i + 1 < address.length(); i += 2) {
                if (!mac.isEmpty()) {
                    mac.append(':');
                }
                mac.append(address, i, i + 2);
            }
            return mac.toString();
        }

        String describe() {
            StringBuilder text = new StringBuilder(kind.label());
            text.append(" · ").append(connected == null ? "état de connexion inconnu"
                    : connected ? "connecté" : "appairé, non connecté");
            if (lastConnected != null) {
                text.append(" · dernière connexion le ").append(lastConnected.format(DAY));
            }
            if (lowEnergy) {
                text.append(" · Bluetooth LE");
            }
            return text.toString();
        }
    }

    /** Drapeau de connexion effective d'un appareil Bluetooth sous Windows. */
    private static final String CONNECTED_KEY = "{83DA6326-97A6-4088-9453-A1923F573B29} 15";

    /**
     * Le script ne contient ni guillemet double ni barre oblique inverse : il passe en un
     * seul argument de ligne de commande, où ces deux caractères se font mutiler. Tout le
     * découpage des identifiants est fait en Java, où il se teste.
     */
    private static final String POWERSHELL_SCRIPT = String.join(" ",
            "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;",
            "$nodes = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |",
            "Where-Object { $_.InstanceId.StartsWith('BTHENUM') -or $_.InstanceId.StartsWith('BTHLE') };",
            "$rows = foreach ($d in $nodes) {",
            "$connected = ''; $last = ''; $cod = '';",
            "if ($d.InstanceId.Contains('DEV_')) {",
            "foreach ($p in (Get-PnpDeviceProperty -InstanceId $d.InstanceId -ErrorAction SilentlyContinue)) {",
            "if ($p.KeyName -eq '" + CONNECTED_KEY + "') { $connected = [string]$p.Data }",
            "elseif ($p.KeyName -eq 'DEVPKEY_Bluetooth_LastConnectedTime' -and $p.Data -is [datetime])"
                    + " { $last = $p.Data.ToString('yyyy-MM-dd') }",
            "elseif ($p.KeyName -eq 'DEVPKEY_Bluetooth_ClassOfDevice') { $cod = [string]$p.Data }",
            "} };",
            "[pscustomobject]@{ InstanceId = $d.InstanceId; Name = $d.FriendlyName;"
                    + " Connected = $connected; LastConnected = $last; ClassOfDevice = $cod }",
            "};",
            "$rows | ConvertTo-Csv -NoTypeInformation");

    /** Le nœud de l'appareil lui-même : BTHENUM\DEV_<adresse> ou BTHLE\DEV_<adresse>. */
    private static final Pattern DEVICE_NODE =
            Pattern.compile("^BTH(ENUM|LE)\\\\DEV_([0-9A-Fa-f]{12})");

    /** Un nœud de service : BTHENUM\{0000XXXX-…}_…&<adresse>_C00000000. */
    private static final Pattern SERVICE_NODE = Pattern.compile(
            "^BTHENUM\\\\\\{0000([0-9A-Fa-f]{4})-.*&([0-9A-Fa-f]{12})_C[0-9A-Fa-f]+$");

    // Services exposés par l'appareil distant. Un casque reçoit l'audio (110B) et parle en
    // mains-libres (111E) ; un téléphone, lui, émet l'audio (110A) et sert le répertoire.
    private static final Set<String> AUDIO_SERVICES = Set.of("110B", "1108", "111E", "110D");
    private static final Set<String> INPUT_SERVICES = Set.of("1124");
    private static final Set<String> PHONE_SERVICES = Set.of("110A", "1105", "112F", "1112", "111F");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    private BluetoothInventory() {
    }

    static List<BluetoothDevice> read() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return List.of(); // seule la lecture Windows existe aujourd'hui
        }
        // Get-PnpDevice puis une lecture de propriétés par appareil : plus long que le
        // délai ordinaire des commandes, d'où une borne dédiée.
        return ShellCommand.output(StandardCharsets.UTF_8, Duration.ofSeconds(20),
                        "powershell", "-NoProfile", "-Command", POWERSHELL_SCRIPT)
                .map(BluetoothInventory::parse)
                .orElseGet(List::of);
    }

    static List<BluetoothDevice> parse(String csv) {
        Map<String, Set<String>> servicesByAddress = new LinkedHashMap<>();
        List<String[]> nodes = new ArrayList<>();

        for (String line : csv.split("\\R")) {
            List<String> fields = HardwareInventory.parseCsvLine(line);
            if (fields.size() < 5 || fields.get(0).equals("InstanceId")) {
                continue;
            }
            String instanceId = fields.get(0);
            Matcher service = SERVICE_NODE.matcher(instanceId);
            if (service.find()) {
                servicesByAddress
                        .computeIfAbsent(service.group(2).toUpperCase(Locale.ROOT), key -> new LinkedHashSet<>())
                        .add(service.group(1).toUpperCase(Locale.ROOT));
                continue;
            }
            Matcher device = DEVICE_NODE.matcher(instanceId);
            if (device.find() && !fields.get(1).isBlank()) {
                nodes.add(new String[] { device.group(2).toUpperCase(Locale.ROOT), fields.get(1),
                        fields.get(2), fields.get(3), fields.get(4), device.group(1) });
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        List<BluetoothDevice> devices = new ArrayList<>();
        for (String[] node : nodes) {
            String address = node[0];
            if (!seen.add(address)) {
                continue; // un même appareil vu deux fois reste un appareil
            }
            devices.add(new BluetoothDevice(address, node[1],
                    kindOf(servicesByAddress.getOrDefault(address, Set.of()), node[4]),
                    connection(node[2]), day(node[3]), node[5].equalsIgnoreCase("LE")));
        }
        return devices;
    }

    static Kind kindOf(Set<String> services, String classOfDevice) {
        if (services.stream().anyMatch(AUDIO_SERVICES::contains)) {
            return Kind.AUDIO;
        }
        if (services.stream().anyMatch(INPUT_SERVICES::contains)) {
            return Kind.INPUT;
        }
        if (services.stream().anyMatch(PHONE_SERVICES::contains)) {
            return Kind.PHONE;
        }
        // Sans service reconnu, la classe d'appareil tranche : ses bits 8 à 12 portent
        // la catégorie majeure attribuée par le Bluetooth SIG.
        try {
            int major = (Integer.parseInt(classOfDevice.trim()) >> 8) & 0x1F;
            return switch (major) {
                case 1 -> Kind.COMPUTER;
                case 2 -> Kind.PHONE;
                case 4 -> Kind.AUDIO;
                case 5 -> Kind.INPUT;
                case 7 -> Kind.WEARABLE;
                default -> Kind.OTHER;
            };
        } catch (NumberFormatException | NullPointerException unknownClass) {
            return Kind.OTHER;
        }
    }

    private static Boolean connection(String value) {
        if ("True".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("False".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null; // Windows n'a rien dit : on ne l'invente pas
    }

    private static LocalDate day(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
