package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import org.mbali.model.AdapterKind;
import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkAdapter;
import org.mbali.model.PortEndpoint;
import org.mbali.model.PortType;

import org.junit.jupiter.api.Test;
import org.mbali.service.NetworkScanner.Subnet;

class NetworkScannerTest {

    @Test
    void enrichesTcpDevicesWithoutMutatingPublishedObjectsOrDuplicatingLocalHost() throws IOException {
        var subnet = new Subnet("192.168.1.1", 30);
        Device initial = new Device("192.168.1.2", "Appareil 2", "192.168.1.2", DeviceType.LAN_PEER);
        initial.addEndpoint(new PortEndpoint(80, "HTTP", PortType.NETWORK_PHYSICAL));
        List<Device> events = new ArrayList<>();
        NetworkScanner.scan(subnet, null, ip -> initial,
                () -> Map.of("192.168.1.2", "AA:BB:CC:DD:EE:02", "192.168.1.1", "AA:BB:CC:DD:EE:01"),
                events::add, done -> {});
        assertEquals(2, events.size());
        assertEquals(Device.UNKNOWN_MAC, initial.getMacAddress());
        Device enriched = events.get(1);
        assertNotSame(initial, enriched);
        assertEquals(initial.getId(), enriched.getId());
        assertEquals("AA:BB:CC:DD:EE:02", enriched.getMacAddress());
        assertEquals(initial.getEndpoints(), enriched.getEndpoints());
    }

    @Test
    void reportsWorkerFailuresAndStillCountsEveryHostAndReadsArp() {
        var subnet = new Subnet("192.168.1.1", 29);
        List<Integer> progress = new ArrayList<>();
        Map<String, Device> found = new ConcurrentHashMap<>();
        IOException failure = assertThrows(IOException.class, () -> NetworkScanner.scan(subnet, null,
                ip -> { throw new IllegalStateException("probe failed"); },
                () -> Map.of("192.168.1.2", "AA:BB:CC:DD:EE:02"),
                device -> found.put(device.getId(), device), progress::add));
        assertEquals(5, failure.getSuppressed().length);
        assertEquals(List.of(1, 2, 3, 4, 5), progress);
        assertEquals(DeviceType.LAN_PEER, found.get("192.168.1.2").getType());
    }

    @Test
    void reportsCallbackFailures() {
        AtomicInteger progress = new AtomicInteger();
        assertThrows(IOException.class, () -> NetworkScanner.scan(new Subnet("192.168.1.1", 30), null,
                ip -> new Device(ip, ip, ip, DeviceType.LAN_PEER), Map::of,
                device -> { throw new IllegalStateException("callback failed"); }, progress::set));
        assertEquals(1, progress.get());
    }

    @Test
    void onlyUsesConfirmedGatewaysInsideTheScanRange() {
        Set<String> hosts = Set.of("192.168.1.1", "192.168.1.254");
        assertNull(NetworkScanner.selectGateway(Optional.empty(), hosts));
        assertNull(NetworkScanner.selectGateway(Optional.of("10.0.0.1"), hosts));
        assertEquals("192.168.1.254", NetworkScanner.selectGateway(Optional.of("192.168.1.254"), hosts));
    }

    @Test
    void listsEveryHostOfASlash24() {
        List<String> hosts = NetworkScanner.hostAddresses(new Subnet("192.168.1.64", 24));

        assertEquals(254, hosts.size());
        assertEquals("192.168.1.1", hosts.get(0));
        assertEquals("192.168.1.254", hosts.get(253));
        // Ni l'adresse réseau ni l'adresse de diffusion ne doivent être balayées
        assertFalse(hosts.contains("192.168.1.0"));
        assertFalse(hosts.contains("192.168.1.255"));
    }

    @Test
    void slash16NetworksAreCoveredCompletely() {
        List<String> hosts = NetworkScanner.hostAddresses(new Subnet("172.25.160.1", 16));

        assertEquals(65_534, hosts.size());
        assertEquals("172.25.0.1", hosts.get(0));
        assertEquals("172.25.255.254", hosts.get(hosts.size() - 1));
    }

    @Test
    void rejectsRangesLargerThanTheExplicitSafetyLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> NetworkScanner.hostAddresses(new Subnet("10.1.2.3", 8)));
    }

    @Test
    void thisMachineNeverAppearsTwiceUnderItsOwnName() {
        // Defaut observe a l ecran : deux noeuds « PC ». La table de voisinage de
        // Windows liste aussi les adresses permanentes de l hote, et le DNS inverse
        // leur rendait le nom de la machine.
        Device local = new Device("local-host", "PC", "192.168.1.64", DeviceType.LOCAL_HOST);
        local.setMacAddress("08:00:27:AB:CD:EF");
        local.addAdapter(new NetworkAdapter("wireless_1", "Realtek Wi-Fi", AdapterKind.WIFI,
                "192.168.1.64", "08:00:27:AB:CD:EF"));
        local.addAdapter(new NetworkAdapter("vEthernet", "Hyper-V", AdapterKind.VIRTUAL,
                "172.25.160.1", "00:15:5D:01:02:03"));

        Device byAddress = new Device("172.25.160.1", "PC", "172.25.160.1", DeviceType.LAN_PEER);
        Device byHardware = new Device("fe80::10", "PC", "fe80::10",
                DeviceType.LAN_PEER);
        byHardware.setMacAddress("08:00:27:ab:cd:ef"); // meme carte, ecrite en minuscules
        Device phone = new Device("fe80::20", "Appareil", "fe80::20", DeviceType.LAN_PEER);
        phone.setMacAddress("02:11:22:33:44:55");

        List<Device> kept = NetworkScanner.withoutLocalHost(
                List.of(local, byAddress, byHardware, phone), local);

        assertEquals(List.of(local, phone), kept);
    }

    @Test
    void withoutAKnownLocalHostNothingIsDiscarded() {
        Device phone = new Device("fe80::e40c", "Appareil", "fe80::e40c", DeviceType.LAN_PEER);
        assertEquals(List.of(phone), NetworkScanner.withoutLocalHost(List.of(phone), null));
    }

    @Test
    void aPlaceholderLocalMacDoesNotDiscardEveryUnresolvedNeighbour() {
        // Sans MAC exploitable pour l hote, la comparaison materielle ne prouve rien :
        // l appliquer quand meme ferait disparaitre tous les voisins non resolus.
        Device local = new Device("local-host", "PC", "192.168.1.64", DeviceType.LOCAL_HOST);
        Device neighbour = new Device("192.168.1.20", "Appareil", "192.168.1.20", DeviceType.LAN_PEER);

        assertEquals(2, NetworkScanner.withoutLocalHost(List.of(local, neighbour), local).size());
    }

    @Test
    void fusingTwoAddressesKeepsEverythingBothHadLearned() {
        // La fusion gardait un seul representant et jetait ce que l autre adresse
        // avait appris : les ports vus en IPv6 disparaissaient de la carte.
        Device ipv4 = new Device("192.168.1.65", "Appareil", "192.168.1.65", DeviceType.LAN_PEER);
        ipv4.setMacAddress("02:11:22:33:44:55");
        ipv4.addEndpoint(new PortEndpoint(80, "HTTP", PortType.NETWORK_PHYSICAL));
        ipv4.addEndpoint(new PortEndpoint(443, "HTTPS", PortType.NETWORK_PHYSICAL));

        Device ipv6 = new Device("fe80::20", "Appareil", "fe80::20", DeviceType.LAN_PEER);
        ipv6.setMacAddress("02:11:22:33:44:55");
        ipv6.addEndpoint(new PortEndpoint(80, "HTTP", PortType.NETWORK_PHYSICAL)); // deja connu
        ipv6.addEndpoint(new PortEndpoint(9100, "Imprimante", PortType.NETWORK_PHYSICAL));
        ipv6.addPeripheral(new org.mbali.model.Peripheral("Manette",
                org.mbali.model.PeripheralKind.CONTROLLER, "HID\\VID_054C"));

        List<Device> merged = NetworkScanner.mergeByHardware(List.of(ipv4, ipv6));

        assertEquals(1, merged.size(), "une seule machine physique");
        Device machine = merged.get(0);
        assertEquals("192.168.1.65", machine.getIpAddress(), "l adresse la plus informative est gardee");
        assertEquals(3, machine.getEndpoints().size(), "80, 443 et 9100, sans doublon sur 80");
        assertEquals(1, machine.getPeripherals().size(), "le materiel vu par l autre adresse est conserve");
        // Les objets publies ne doivent pas avoir ete modifies au passage
        assertEquals(2, ipv4.getEndpoints().size());
        assertEquals(0, ipv4.getPeripherals().size());
    }

    @Test
    void theSameCardWrittenTwoWaysIsStillOneMachine() {
        // Windows ecrit « AA-BB-CC », Linux « aa:bb:cc » : sans normalisation, la meme
        // carte restait deux appareils distincts sur la carte.
        Device windowsStyle = new Device("192.168.1.70", "A", "192.168.1.70", DeviceType.LAN_PEER);
        windowsStyle.setMacAddress("AA-BB-CC-DD-EE-01");
        Device linuxStyle = new Device("fe80::70", "B", "fe80::70", DeviceType.LAN_PEER);
        linuxStyle.setMacAddress("aa:bb:cc:dd:ee:01");

        assertEquals(1, NetworkScanner.mergeByHardware(List.of(windowsStyle, linuxStyle)).size());
        assertEquals("AA:BB:CC:DD:EE:01", NetworkScanner.normaliseMac("aa-bb-cc-dd-ee-01"));
        assertNull(NetworkScanner.normaliseMac("00:00:00:00:00:00"));
    }

    @Test
    void mergingKeepsTheAdaptersOfBothAddressesWithoutDuplicating() {
        NetworkAdapter card = new NetworkAdapter("wlan0", "Realtek", AdapterKind.WIFI,
                "192.168.1.65", "02:11:22:33:44:55");
        Device first = new Device("192.168.1.65", "A", "192.168.1.65", DeviceType.LAN_PEER);
        first.setMacAddress("02:11:22:33:44:55");
        first.addAdapter(card);
        Device second = new Device("fe80::20", "B", "fe80::20", DeviceType.LAN_PEER);
        second.setMacAddress("02:11:22:33:44:55");
        second.addAdapter(card); // meme carte, annoncee deux fois

        List<Device> merged = NetworkScanner.mergeByHardware(List.of(first, second));

        assertEquals(1, merged.size());
        assertEquals(1, merged.get(0).getAdapters().size(), "une carte annoncee deux fois reste une");
        assertTrue(merged.get(0).getAdapters().contains(card));
    }

    @Test
    void knownPortsGetAReadableName() {
        assertEquals("TCP 80 · probablement HTTP", NetworkScanner.serviceName(80));
        assertEquals("TCP 445 · probablement Partage SMB", NetworkScanner.serviceName(445));
        assertEquals("TCP 12345", NetworkScanner.serviceName(12345));
    }
}
