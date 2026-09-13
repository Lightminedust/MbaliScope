package org.mbali.service;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import java.util.Locale;

import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkAdapter;
import org.mbali.model.Peripheral;
import org.mbali.model.PortEndpoint;
import org.mbali.model.PortType;
import org.mbali.model.Presence;
import org.mbali.service.PortScanner.PortStatus;

/**
 * Découverte des appareils du réseau local. S'appuie sur PortScanner.scanPort
 * et produit des Device prêts pour le modèle, sans dépendance JavaFX.
 */
public final class NetworkScanner {

    /** Adresse locale et longueur du préfixe réseau (ex. 192.168.1.64 et 24). */
    public record Subnet(String localAddress, int prefixLength) {
    }

    static final int[] COMMON_PORTS = { 80, 443, 445, 22, 53, 139, 554, 631, 3306, 5000, 8080, 8443, 9100 };

    /**
     * Un /16 représente déjà 65 534 hôtes. Les réseaux plus vastes doivent être
     * découpés explicitement pour éviter un balayage involontaire de millions d'IP.
     */
    static final int MIN_PREFIX_LENGTH = 16;

    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int MAX_CONCURRENT_HOSTS = 96;

    private NetworkScanner() {
    }

    /** Déduit le sous-réseau à balayer de l'interface qui porte la route par défaut. */
    public static Subnet detect() throws IOException {
        InetAddress primary = SystemScanner.findPrimaryAddress();
        if (!(primary instanceof Inet4Address)) {
            throw new IOException("Aucune adresse IPv4 utilisable");
        }
        int prefixLength = MIN_PREFIX_LENGTH;
        NetworkInterface netInterface = NetworkInterface.getByInetAddress(primary);
        if (netInterface != null) {
            for (InterfaceAddress address : netInterface.getInterfaceAddresses()) {
                if (address.getAddress().equals(primary)) {
                    prefixLength = address.getNetworkPrefixLength();
                    break;
                }
            }
        }
        return new Subnet(primary.getHostAddress(), prefixLength);
    }

    /**
     * Balaye tout le sous-réseau. Chaque appareil trouvé est transmis à onFound sans
     * attendre la fin du scan, et onProgress reçoit le nombre d'adresses traitées.
     * Les deux rappels sont appelés depuis les threads de scan. onFound peut
     * republier une même IP pour enrichir sa MAC ; le consommateur doit la remplacer.
     */
    public static void scan(Subnet subnet, Consumer<Device> onFound, IntConsumer onProgress) throws IOException {
        scan(subnet, onFound, onProgress, () -> false);
    }

    /** Balayage annulable ; les voisins IPv6 connus sont ajoutés après le sondage IPv4. */
    public static void scan(Subnet subnet, Consumer<Device> onFound, IntConsumer onProgress,
                            BooleanSupplier cancelled) throws IOException {
        Set<String> scannable = new LinkedHashSet<>(hostAddresses(subnet));
        String gateway = selectGateway(RoutingTable.defaultGateway(), scannable);
        scan(subnet, gateway,
                host -> probeDiscovery(host, subnet.localAddress(), gateway, cancelled),
                host -> probeKnownHost(host, subnet.localAddress(), gateway, cancelled),
                () -> {
                    // Réveiller le lien avant de lire la table : un appareil en veille y
                    // figurait vieilli, sans son adresse IPv6.
                    NeighborTable.wakeLink(subnet.localAddress());
                    return NeighborTable.read();
                }, onFound, onProgress, cancelled);
    }

    // Sources injectables pour tester la découverte sans balayer un réseau réel.
    static void scan(Subnet subnet, String gateway, Function<String, Device> probe,
                     Supplier<Map<String, String>> arp, Consumer<Device> onFound,
                     IntConsumer onProgress) throws IOException {
        scan(subnet, gateway, probe, host -> null, arp, onFound, onProgress, () -> false);
    }

    private static void scan(Subnet subnet, String gateway, Function<String, Device> probe,
                     Function<String, Device> neighborProbe,
                     Supplier<Map<String, String>> neighbors, Consumer<Device> onFound,
                     IntConsumer onProgress, BooleanSupplier cancelled) throws IOException {
        Set<String> scannable = new LinkedHashSet<>(hostAddresses(subnet));
        scannable.remove(subnet.localAddress());
        Map<String, Device> discovered = new ConcurrentHashMap<>();
        AtomicInteger processed = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(MAX_CONCURRENT_HOSTS, Math.max(scannable.size(), 1)));
        try {
            for (String host : scannable) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                tasks.add(pool.submit(() -> {
                    try {
                        if (cancelled.getAsBoolean()) {
                            return;
                        }
                        Device device = probe.apply(host);
                        if (device != null) {
                            discovered.put(host, device);
                            onFound.accept(device);
                        }
                    } finally {
                        // Sérialise aussi les notifications : la progression ne recule pas.
                        synchronized (processed) {
                            onProgress.accept(processed.incrementAndGet());
                        }
                    }
                }));
            }
        } finally {
            pool.shutdown();
        }
        IOException failure = null;
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (CancellationException e) {
                // Une tâche annulée ne transforme pas l'arrêt demandé en erreur.
            } catch (ExecutionException e) {
                if (failure == null) {
                    failure = new IOException("Échec du sondage d'une ou plusieurs adresses");
                }
                failure.addSuppressed(e.getCause());
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (cancelled.getAsBoolean()) {
            pool.shutdownNow();
            return;
        }
        neighbors.get().forEach((ip, mac) -> {
            if (!ip.equals(subnet.localAddress()) && (ip.contains(":") || scannable.contains(ip))) {
                Device previous = discovered.get(ip);
                if (previous == null && !ip.contains(":") && !cancelled.getAsBoolean()) {
                    previous = neighborProbe.apply(ip);
                }
                // Publie un nouvel objet : l'ancien peut déjà être lu par JavaFX.
                Device device = newDevice(ip, gateway);
                if (previous != null) {
                    previous.getEndpoints().forEach(device::addEndpoint);
                    previous.getAdapters().forEach(device::addAdapter);
                    previous.getPeripherals().forEach(device::addPeripheral);
                }
                device.setMacAddress(mac);
                onFound.accept(device);
            }
        });
        if (failure != null) {
            throw failure;
        }
    }

    private static Device probeDiscovery(String ip, String localAddress, String gateway,
                                         BooleanSupplier cancelled) {
        if (ip.equals(localAddress) || cancelled.getAsBoolean()) return null;
        PortStatus first = PortScanner.scanPort(ip, COMMON_PORTS[0], CONNECT_TIMEOUT_MS);
        if (first == PortStatus.FILTRE) {
            // La tentative suffit à peupler ARP/NDP ; les voisins réels sont enrichis en phase 2.
            return null;
        }
        return probeKnownHost(ip, localAddress, gateway, cancelled, first);
    }

    private static Device probeKnownHost(String ip, String localAddress, String gateway,
                                         BooleanSupplier cancelled) {
        return probeKnownHost(ip, localAddress, gateway, cancelled, null);
    }

    private static Device probeKnownHost(String ip, String localAddress, String gateway,
                                         BooleanSupplier cancelled, PortStatus firstStatus) {
        if (ip.equals(localAddress)) {
            return null; // déjà représenté au centre du radar
        }

        List<Integer> openPorts = new ArrayList<>();
        boolean alive = false;
        for (int index = 0; index < COMMON_PORTS.length; index++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return null;
            }
            int port = COMMON_PORTS[index];
            PortStatus status = index == 0 && firstStatus != null
                    ? firstStatus : PortScanner.scanPort(ip, port, CONNECT_TIMEOUT_MS);
            if (status == PortStatus.OUVERT) {
                openPorts.add(port);
                alive = true;
            } else if (status == PortStatus.FERME) {
                // Un refus de connexion prouve qu'une machine répond, même sans port ouvert
                alive = true;
            }
        }
        if (!alive) {
            return null;
        }

        Device device = newDevice(ip, gateway);
        for (int port : openPorts) {
            ServiceProbe.Fingerprint fingerprint = ServiceProbe.identify(ip, port, CONNECT_TIMEOUT_MS);
            device.addEndpoint(new PortEndpoint(port, fingerprint.label(), PortType.NETWORK_PHYSICAL,
                    PortEndpoint.UNKNOWN_OWNER, ip, fingerprint.evidence(), fingerprint.verified()));
        }
        return device;
    }

    /** Découverte passive utilisable sur une machine IPv6 sans adresse IPv4. */
    public static void scanKnownNeighbors(String localAddress, Consumer<Device> onFound) {
        NeighborTable.read().forEach((ip, mac) -> {
            if (!ip.equals(localAddress)) {
                Device device = newDevice(ip, null);
                device.setMacAddress(mac);
                onFound.accept(device);
            }
        });
    }

    /**
     * Donne un nom aux appareils déjà découverts, puis les republie.
     *
     * C'est une passe distincte, et non un travail fait pendant le sondage : le
     * balayage SSDP dure environ deux secondes et demie, et une résolution DNS
     * inverse qui échoue bloque plusieurs centaines de millisecondes. Les mêler au
     * sondage retarderait l'apparition des appareils sur la carte, alors qu'ici la
     * carte est déjà peuplée et ne fait que se préciser.
     *
     * Le nom de Device étant définitif à la construction, chaque appareil renommé est
     * un nouvel objet : l'ancien peut déjà être lu par le thread graphique.
     */
    public static void resolveNames(Collection<Device> devices, String gateway,
                                    Consumer<Device> onNamed, BooleanSupplier cancelled) {
        if (devices.isEmpty() || cancelled.getAsBoolean()) {
            return;
        }
        // Le balayage SSDP dure plusieurs secondes : sans ce jeton, il continuait
        // après un arrêt demandé, et s'empilait au fil des actualisations.
        Map<String, NameResolver.Identity> announced = NameResolver.discoverUpnp(cancelled);
        for (Device device : devices) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            boolean isGateway = device.getType() == DeviceType.GATEWAY_ROUTER
                    || device.getIpAddress().equals(gateway);
            NameResolver.Identity identity =
                    NameResolver.resolve(device.getIpAddress(), device.getMacAddress(),
                            isGateway, announced);
            if (identity.name().equals(device.getName())) {
                continue; // rien de nouveau à annoncer
            }
            Device renamed = new Device(device.getId(), identity.name(),
                    device.getIpAddress(), device.getType());
            renamed.setMacAddress(device.getMacAddress());
            renamed.setPresence(device.getPresence());
            // La preuve du nom voyage désormais avec l'appareil : l'inspecteur dit d'où
            // vient ce nom, ou pourquoi il manque, au lieu de la jeter ici.
            renamed.setEvidence(identity.evidence());
            device.getEndpoints().forEach(renamed::addEndpoint);
            device.getAdapters().forEach(renamed::addAdapter);
            device.getPeripherals().forEach(renamed::addPeripheral);
            onNamed.accept(renamed);
        }
    }

    private static Device newDevice(String ip, String gateway) {
        boolean isGateway = ip.equals(gateway);
        String name = isGateway ? "Passerelle" : "Appareil " + addressSuffix(ip);
        return new Device(ip, name, ip, isGateway ? DeviceType.GATEWAY_ROUTER : DeviceType.LAN_PEER);
    }

    /**
     * Regroupe les appareils par adresse MAC : une machine physique n'est qu'un seul
     * appareil, quel que soit le nombre d'adresses qu'elle détient.
     *
     * C'est indispensable en IPv6 : un téléphone conserve son adresse de lien local
     * et plusieurs adresses temporaires de confidentialité simultanément, si bien
     * qu'un même appareil apparaissait trois fois sur la carte. Sur un réseau réel,
     * quinze « appareils » se sont révélés être trois machines.
     *
     * Fonction pure, sans accès réseau : elle se teste sans rien balayer. Les
     * appareils dont la MAC est inconnue gardent leur identité propre, faute de
     * pouvoir prouver qu'ils sont le même matériel.
     */
    public static List<Device> mergeByHardware(Collection<Device> devices) {
        Map<String, Device> byHardware = new LinkedHashMap<>();
        List<Device> merged = new ArrayList<>();

        for (Device device : devices) {
            // La clé est normalisée : Windows écrit « AA-BB-CC », Linux « aa:bb:cc »,
            // et deux écritures de la même carte ne doivent pas rester deux machines.
            String mac = normaliseMac(device.getMacAddress());
            // Une adresse nulle ou factice ne prouve rien : sans matériel identifiable,
            // chaque appareil garde son identité au lieu d'être fondu avec les autres.
            if (mac == null) {
                merged.add(device);
                continue;
            }
            Device kept = byHardware.get(mac);
            if (kept == null) {
                byHardware.put(mac, device);
                merged.add(device);
            } else {
                // On garde le représentant le plus informatif, mais on ne jette pas ce
                // que l'autre adresse avait appris : ses ports et son matériel sont
                // ceux de la même machine, et les perdre appauvrissait la carte.
                Device winner = preferable(device, kept) ? device : kept;
                Device union = absorb(winner, winner == device ? kept : device);
                merged.set(merged.indexOf(kept), union);
                byHardware.put(mac, union);
            }
        }
        return merged;
    }

    /** Adresse MAC ramenée à une écriture unique, ou null si elle ne prouve rien. */
    static String normaliseMac(String mac) {
        if (!NameResolver.isUsableMac(mac)) {
            return null;
        }
        return mac.replace('-', ':').toUpperCase(Locale.ROOT);
    }

    /**
     * Réunit ce que deux adresses d'une même machine ont appris, sous l'identité de la
     * plus informative. Un nouvel objet est construit : celui d'origine peut déjà être
     * lu par le thread graphique.
     *
     * PortEndpoint n'ayant pas d'equals, la déduplication se fait sur une clé — la même
     * que celle des satellites de la carte, pour que les deux vues concordent.
     */
    private static Device absorb(Device kept, Device other) {
        Device union = new Device(kept.getId(), kept.getName(), kept.getIpAddress(), kept.getType());
        union.setMacAddress(kept.getMacAddress());
        // Il suffit qu'une adresse de la machine soit active pour que la machine le soit.
        union.setPresence(kept.getPresence() == Presence.ACTIVE || other.getPresence() == Presence.ACTIVE
                ? Presence.ACTIVE : Presence.DORMANT);
        union.setEvidence(kept.getEvidence().isBlank() ? other.getEvidence() : kept.getEvidence());

        Set<String> ports = new LinkedHashSet<>();
        Set<NetworkAdapter> adapters = new LinkedHashSet<>();
        Set<String> hardware = new LinkedHashSet<>();
        for (Device source : List.of(kept, other)) {
            for (PortEndpoint port : source.getEndpoints()) {
                if (ports.add(portKey(port))) {
                    union.addEndpoint(port);
                }
            }
            for (NetworkAdapter adapter : source.getAdapters()) {
                if (adapters.add(adapter)) {
                    union.addAdapter(adapter);
                }
            }
            for (Peripheral peripheral : source.getPeripherals()) {
                if (hardware.add(peripheral.hardwareId())) {
                    union.addPeripheral(peripheral);
                }
            }
        }
        return union;
    }

    private static String portKey(PortEndpoint port) {
        return port.getPortNumber() + ":" + port.getLocalAddress() + ":" + port.getOwner();
    }

    /**
     * Écarte les adresses qui sont celles de cette machine.
     *
     * La table de voisinage de Windows contient aussi les entrées permanentes de
     * l'hôte : ses propres adresses IPv6 revenaient donc comme un voisin, et le DNS
     * inverse leur donnait le nom de la machine. Le même ordinateur apparaissait deux
     * fois sur la carte, sous le même nom.
     *
     * Deux preuves, complémentaires : l'adresse, qui reconnaît aussi les interfaces
     * virtuelles, et la MAC, qui reconnaît une adresse IPv6 écrite autrement (forme
     * développée, identifiant de portée) et que la comparaison de texte manquerait.
     *
     * Fonction pure, sans accès réseau : elle se teste sans rien balayer.
     */
    public static List<Device> withoutLocalHost(Collection<Device> devices, Device localHost) {
        if (localHost == null) {
            return new ArrayList<>(devices);
        }
        Set<String> ownAddresses = new LinkedHashSet<>();
        ownAddresses.add(localHost.getIpAddress());
        localHost.getAdapters().forEach(adapter -> ownAddresses.add(adapter.ipAddress()));
        String ownMac = localHost.getMacAddress();

        List<Device> kept = new ArrayList<>();
        for (Device device : devices) {
            if (device.equals(localHost)) {
                kept.add(device); // l'hôte lui-même garde sa place
                continue;
            }
            if (ownAddresses.contains(device.getIpAddress())) {
                continue;
            }
            if (NameResolver.isUsableMac(ownMac) && ownMac.equalsIgnoreCase(device.getMacAddress())) {
                continue;
            }
            kept.add(device);
        }
        return kept;
    }

    /**
     * Entre deux adresses de la même machine, on retient celle qui en dit le plus :
     * d'abord la passerelle, puis celle qui expose des ports, puis l'IPv4 — plus
     * lisible qu'une IPv6 temporaire.
     */
    private static boolean preferable(Device candidate, Device kept) {
        if (candidate.getType() == DeviceType.GATEWAY_ROUTER
                && kept.getType() != DeviceType.GATEWAY_ROUTER) {
            return true;
        }
        if (kept.getType() == DeviceType.GATEWAY_ROUTER) {
            return false;
        }
        int byPorts = Integer.compare(candidate.getEndpoints().size(), kept.getEndpoints().size());
        if (byPorts != 0) {
            return byPorts > 0;
        }
        boolean candidateIsIpv4 = !candidate.getIpAddress().contains(":");
        boolean keptIsIpv4 = !kept.getIpAddress().contains(":");
        return candidateIsIpv4 && !keptIsIpv4;
    }

    /** Ne classe un appareil comme passerelle que si le système l'identifie. */
    static String selectGateway(Optional<String> gateway, Set<String> scannable) {
        return gateway.filter(scannable::contains).orElse(null);
    }

    /** Toutes les adresses d'hôte du sous-réseau, hors adresse réseau et adresse de diffusion. */
    public static List<String> hostAddresses(Subnet subnet) {
        if (subnet.prefixLength() < MIN_PREFIX_LENGTH) {
            throw new IllegalArgumentException("Réseau /" + subnet.prefixLength()
                    + " trop vaste : la limite sûre est /" + MIN_PREFIX_LENGTH);
        }
        int prefixLength = subnet.prefixLength();
        int mask = -1 << (32 - prefixLength);
        int network = toInt(subnet.localAddress()) & mask;
        int broadcast = network | ~mask;

        List<String> hosts = new ArrayList<>();
        // Comparaison non signée : 192.168.x.y dépasse Integer.MAX_VALUE une fois encodé
        for (int address = network + 1; Integer.compareUnsigned(address, broadcast) < 0; address++) {
            hosts.add(toIpv4(address));
        }
        return hosts;
    }

    static String serviceHint(int port) {
        return switch (port) {
            case 22 -> "SSH";
            case 53 -> "DNS";
            case 80 -> "HTTP";
            case 139 -> "NetBIOS";
            case 443 -> "HTTPS";
            case 445 -> "Partage SMB";
            case 554 -> "Flux RTSP";
            case 631 -> "Impression IPP";
            case 3306 -> "MySQL";
            case 5000 -> "UPnP / API";
            case 8080 -> "HTTP alt.";
            case 8443 -> "HTTPS alt.";
            case 9100 -> "Imprimante";
            default -> null;
        };
    }

    static String serviceName(int port) {
        String hint = serviceHint(port);
        return hint == null ? "TCP " + port : "TCP " + port + " · probablement " + hint;
    }

    private static String addressSuffix(String address) {
        int separator = Math.max(address.lastIndexOf('.'), address.lastIndexOf(':'));
        return separator < 0 ? address : address.substring(separator + 1);
    }

    private static int toInt(String ipv4) {
        int value = 0;
        for (String part : ipv4.split("\\.")) {
            value = (value << 8) | Integer.parseInt(part);
        }
        return value;
    }

    private static String toIpv4(int value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "."
                + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
