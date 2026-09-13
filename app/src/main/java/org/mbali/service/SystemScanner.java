package org.mbali.service;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.lang.management.ManagementFactory;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

import org.mbali.model.AdapterKind;
import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkAdapter;
import org.mbali.model.Peripheral;
import org.mbali.model.Presence;
import org.mbali.model.ProcessSnapshot;
import org.mbali.model.PortEndpoint;
import org.mbali.model.PortType;

public final class SystemScanner {

    private SystemScanner() {
    }

    /**
     * Inventaire complet de la machine locale : cartes réseau, ports en écoute avec
     * le processus qui les détient, et matériel branché.
     */
    public static Device scanLocalHost() {
        Device localDevice = scanNetworkIdentity();
        addListeningPorts(localDevice);
        for (Peripheral peripheral : HardwareInventory.read()) {
            localDevice.addPeripheral(peripheral);
        }
        return localDevice;
    }

    /**
     * Les appareils Bluetooth appairés à cette machine. Chacun devient un appareil à part
     * entière, qui gravitera autour d'elle comme un client réseau autour de sa box.
     */
    public static List<Device> scanBluetooth() {
        List<Device> devices = new ArrayList<>();
        for (BluetoothInventory.BluetoothDevice paired : BluetoothInventory.read()) {
            devices.add(toDevice(paired));
        }
        return devices;
    }

    /**
     * Les processus de la machine à cet instant. Façade publique : la lecture elle-même
     * reste interne au paquet service.
     */
    public static List<ProcessSnapshot> scanProcesses() {
        return ProcessReader.read();
    }

    /** Mémoire physique totale, pour exprimer celle d'une application en pourcentage. */
    public static OptionalLong totalMemoryBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean system) {
                long total = system.getTotalMemorySize();
                return total > 0 ? OptionalLong.of(total) : OptionalLong.empty();
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // JVM qui n'expose pas cette mesure : les parts se rapporteront à la mémoire observée
        }
        return OptionalLong.empty();
    }

    static Device toDevice(BluetoothInventory.BluetoothDevice paired) {
        // Pas d'adresse IP : la liaison est radio. Le champ porte donc la nature du lien.
        Device device = new Device("bt:" + paired.address(), paired.name(), "Bluetooth",
                DeviceType.BLUETOOTH);
        device.setMacAddress(paired.formattedAddress());
        // Seule une connexion affirmée rend l'appareil actif : un état inconnu n'en est pas une.
        device.setPresence(Boolean.TRUE.equals(paired.connected()) ? Presence.ACTIVE : Presence.DORMANT);
        device.setEvidence(paired.describe());
        return device;
    }

    private static Device scanNetworkIdentity() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            InetAddress primary = findPrimaryAddress();
            Device localDevice = new Device("local-host", hostname, primary.getHostAddress(), DeviceType.LOCAL_HOST);

            // MAC de l'interface qui porte l'IP principale, et non de la dernière énumérée
            NetworkInterface primaryInterface = NetworkInterface.getByInetAddress(primary);
            if (primaryInterface != null) {
                localDevice.setMacAddress(formatMac(primaryInterface.getHardwareAddress()));
            }

            for (NetworkInterface netInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!netInterface.isUp() || netInterface.isLoopback()) {
                    continue;
                }
                // Une entrée par adresse rend également visibles les interfaces IPv6.
                for (InetAddress addr : Collections.list(netInterface.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && !addr.isAnyLocalAddress()) {
                        localDevice.addAdapter(new NetworkAdapter(
                                netInterface.getName(),
                                netInterface.getDisplayName(),
                                classify(netInterface.getName(), netInterface.getDisplayName()),
                                addr.getHostAddress(),
                                formatMac(netInterface.getHardwareAddress())));
                    }
                }
            }

            return localDevice;
        } catch (IOException e) {
            e.printStackTrace();
            return new Device("local-host", "PC Local", "127.0.0.1", DeviceType.LOCAL_HOST);
        }
    }

    private static void addListeningPorts(Device device) {
        var ports = ListeningPorts.read();
        if (ports.isEmpty()) {
            return;
        }
        Map<Integer, String> processes = ProcessTable.read();
        ports.forEach(listener -> device.addEndpoint(new PortEndpoint(
                listener.port(),
                NetworkScanner.serviceName(listener.port()),
                PortType.NETWORK_PHYSICAL,
                processes.getOrDefault(listener.pid(), "PID " + listener.pid()),
                listener.address())));
    }

    /**
     * connect() sur un socket UDP n'émet aucun paquet : l'OS choisit seulement la route,
     * ce qui révèle l'adresse de l'interface réellement utilisée vers l'extérieur.
     * Plus fiable que getLocalHost(), qui peut renvoyer une IP Hyper-V/WSL sous Windows.
     */
    static InetAddress findPrimaryAddress() throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("8.8.8.8", 53));
            InetAddress local = socket.getLocalAddress();
            if (!local.isAnyLocalAddress()) {
                return local;
            }
        } catch (IOException e) {
            // Pas de route vers l'extérieur (hors ligne) : repli sur le nom d'hôte
        }
        return InetAddress.getLocalHost();
    }

    static AdapterKind classify(String name, String displayName) {
        String n = name.toLowerCase(Locale.ROOT);
        String dn = displayName.toLowerCase(Locale.ROOT);

        // Le virtuel doit être testé en premier : "Hyper-V Virtual Ethernet Adapter"
        // contient aussi "ethernet" et serait sinon classé comme carte filaire.
        if (containsAny(dn, "virtual", "hyper-v", "vethernet", "vpn", "tap-", "wireguard", "vmware", "virtualbox", "wsl")
                || startsWithAny(n, "tun", "tap", "veth", "docker", "br-", "virbr", "vmnet", "utun")) {
            return AdapterKind.VIRTUAL;
        }
        // Windows écrit "WiFi" sans tiret, et nomme ses cartes "wireless_N"
        if (containsAny(dn, "wi-fi", "wifi", "wireless", "wlan", "802.11")
                || startsWithAny(n, "wireless", "wlan", "wl")) {
            return AdapterKind.WIFI;
        }
        if (containsAny(dn, "ethernet", "gigabit")
                || startsWithAny(n, "ethernet", "eth", "en")) {
            return AdapterKind.ETHERNET;
        }
        return AdapterKind.OTHER;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String formatMac(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Device.UNKNOWN_MAC;
        }
        StringBuilder mac = new StringBuilder();
        for (byte b : bytes) {
            if (!mac.isEmpty()) {
                mac.append(':');
            }
            mac.append(String.format("%02X", b));
        }
        return mac.toString();
    }
}
