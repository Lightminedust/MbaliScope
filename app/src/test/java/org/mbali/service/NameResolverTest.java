package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.PortEndpoint;
import org.mbali.model.PortType;
import org.mbali.service.NameResolver.Identity;

class NameResolverTest {

    // Adresses fictives conservant les propriétés utiles aux tests.
    private static final String PHONE_ONE = "02:11:22:33:44:55";
    private static final String PHONE_TWO = "06:AA:BB:CC:DD:EE";
    private static final String GATEWAY = "00:11:22:33:44:66";
    private static final String LOCAL_PC = "08:00:27:12:34:56";

    @Test
    void recognisesTheRandomisedMacsOfModernPhones() {
        // Le bit 0x02 du premier octet est levé : l'appareil tire son adresse au hasard
        assertTrue(NameResolver.isRandomMac(PHONE_ONE), "0x02 porte le bit local");
        assertTrue(NameResolver.isRandomMac(PHONE_TWO), "0x06 porte le bit local");
        assertFalse(NameResolver.isRandomMac(GATEWAY), "0x00 est une adresse universelle");
        assertFalse(NameResolver.isRandomMac(LOCAL_PC), "0x08 est une adresse universelle");
    }

    @Test
    void onlyDerivesAVendorFromAUniversalAddress() {
        assertEquals("00:11:22", NameResolver.vendorPrefix(GATEWAY));
        assertEquals("08:00:27", NameResolver.vendorPrefix(LOCAL_PC));
        assertNull(NameResolver.vendorPrefix(PHONE_ONE), "une MAC aléatoire ne désigne aucun constructeur");
        assertNull(NameResolver.vendorPrefix(null));
    }

    @Test
    void anAnnouncedNameWinsOverEverythingElse() {
        Map<String, Identity> upnp = Map.of("192.168.1.254",
                new Identity("Internet Home Gateway Device", "UPnP · Nokia · IGD Version 2.00"));

        Identity identity = NameResolver.resolve("192.168.1.254", GATEWAY, true, upnp);

        assertEquals("Internet Home Gateway Device", identity.name());
        assertTrue(identity.evidence().contains("Nokia"));
    }

    @Test
    void saysWhyTheNameIsMissingInsteadOfLookingBroken() {
        // Adresse de documentation : aucun PTR ne peut répondre
        Identity identity = NameResolver.resolve("2001:db8::5", PHONE_ONE, false, Map.of());

        assertEquals("Appareil · MAC aléatoire", identity.name());
        assertTrue(identity.evidence().contains("privée"),
                "l'utilisateur doit lire la raison, pas subir un libellé vide");
    }

    @Test
    void fallsBackToTheVendorWhenTheAddressIsUniversal() {
        Identity identity = NameResolver.resolve("2001:db8::7", GATEWAY, false, Map.of());

        assertEquals("Appareil 00:11:22", identity.name());
        // On annonce un prefixe OUI, pas une marque : c'est tout ce que l'on sait
        assertTrue(identity.evidence().contains("OUI"));
        assertTrue(identity.evidence().contains("non résolue"));
    }

    @Test
    void readsTheFriendlyNameFromAUpnpDescription() {
        String document = """
                <?xml version="1.0"?>
                <root><device>
                <friendlyName>Internet Home Gateway Device</friendlyName>
                <manufacturer>Nokia</manufacturer>
                <modelName>IGD Version 2.00</modelName>
                </device></root>
                """;

        assertEquals("Internet Home Gateway Device",
                NameResolver.between(document, "<friendlyName>", "</friendlyName>"));
        assertEquals("Nokia", NameResolver.between(document, "<manufacturer>", "</manufacturer>"));
        assertEquals("", NameResolver.between(document, "<serialNumber>", "</serialNumber>"));
    }

    @Test
    void onlyFetchesDescriptionsFromTheDeviceThatAnnouncedThem() {
        // L URL vient d un en-tete envoye par une machine du reseau, que l on ne
        // controle pas : sans controle, elle pouvait nous faire emettre une requete
        // vers n importe quelle cible.
        assertTrue(NameResolver.allowedDescriptionUrl(
                "http://192.168.1.254:49152/gatedesc.xml", "192.168.1.254"));

        assertFalse(NameResolver.allowedDescriptionUrl(
                "http://10.0.0.9:80/steal.xml", "192.168.1.254"), "un autre hote");
        assertFalse(NameResolver.allowedDescriptionUrl(
                "https://192.168.1.254/desc.xml", "192.168.1.254"), "un autre protocole");
        assertFalse(NameResolver.allowedDescriptionUrl(
                "file:///C:/Windows/win.ini", "192.168.1.254"), "un fichier local");
        assertFalse(NameResolver.allowedDescriptionUrl(
                "http://exemple.test/desc.xml", "192.168.1.254"),
                "un nom d hote supposerait une resolution DNS pilotee par l appareil");
        assertFalse(NameResolver.allowedDescriptionUrl("pas une url", "192.168.1.254"));
    }

    @Test
    void acceptsTheBracketedFormOfAnIpv6Announcement() {
        assertTrue(NameResolver.allowedDescriptionUrl(
                "http://[fe80::1]:49152/desc.xml", "fe80::1"));
        assertFalse(NameResolver.allowedDescriptionUrl(
                "http://[fe80::2]:49152/desc.xml", "fe80::1"));
    }

    @Test
    void readsTheLocationHeaderOfAnSsdpReply() {
        String reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n"
                + "LOCATION: http://192.168.1.254:49152/gatedesc.xml\r\nST: upnp:rootdevice\r\n\r\n";

        assertEquals("http://192.168.1.254:49152/gatedesc.xml",
                NameResolver.header(reply, "LOCATION:"));
        assertEquals("", NameResolver.header(reply, "SERVER:"));
    }

    @Test
    void threeAddressesOfOnePhoneCollapseIntoASingleDevice() {
        // Cas réel : un téléphone détient son adresse de lien local et deux adresses
        // temporaires de confidentialité, toutes derrière la même carte réseau.
        List<Device> devices = List.of(
                withMac(device("fe80::11"), PHONE_ONE),
                withMac(device("2001:db8:1::10"), PHONE_ONE),
                withMac(device("2001:db8:1::11"), PHONE_ONE));

        List<Device> merged = NetworkScanner.mergeByHardware(devices);

        assertEquals(1, merged.size(), "une machine physique, un seul appareil");
    }

    @Test
    void fifteenAddressesResolveToThreeRealMachines() {
        List<Device> devices = List.of(
                withMac(device("fe80::11"), PHONE_ONE),
                withMac(device("2001:db8:1::10"), PHONE_ONE),
                withMac(device("2001:db8:1::11"), PHONE_ONE),
                withMac(device("fe80::21"), PHONE_TWO),
                withMac(device("2001:db8:2::20"), PHONE_TWO),
                withMac(device("2001:db8:2::21"), PHONE_TWO),
                withMac(device("fe80::1"), GATEWAY),
                withMac(device("2001:db8:3::1"), GATEWAY));

        assertEquals(3, NetworkScanner.mergeByHardware(devices).size());
    }

    @Test
    void theKeptRepresentativeIsTheMostInformativeAddress() {
        Device bare = withMac(device("fe80::11"), PHONE_ONE);
        Device withPorts = withMac(device("192.168.1.65"), PHONE_ONE);
        withPorts.addEndpoint(new PortEndpoint(80, "HTTP", PortType.NETWORK_PHYSICAL));

        List<Device> merged = NetworkScanner.mergeByHardware(List.of(bare, withPorts));

        assertEquals(1, merged.size());
        assertEquals("192.168.1.65", merged.get(0).getIpAddress(),
                "l'adresse qui expose des ports en dit davantage");
    }

    @Test
    void devicesWithoutAKnownMacKeepTheirOwnIdentity() {
        // Sans preuve matérielle, on ne peut pas affirmer qu'il s'agit du même appareil
        List<Device> merged = NetworkScanner.mergeByHardware(List.of(
                device("192.168.1.10"), device("192.168.1.11")));

        assertEquals(2, merged.size());
    }

    @Test
    void anAllZeroMacIsAPlaceholderAndNotAHardwareAddress() {
        // La table de voisinage renvoie parfois 00:00:00:00:00:00 tant que le voisin
        // n'est pas resolu : ce n'est pas une carte reseau.
        assertFalse(NameResolver.isUsableMac("00:00:00:00:00:00"));
        assertFalse(NameResolver.isUsableMac(Device.UNKNOWN_MAC));
        assertFalse(NameResolver.isUsableMac(null));
        assertFalse(NameResolver.isUsableMac(""));
        assertTrue(NameResolver.isUsableMac(GATEWAY));
        assertTrue(NameResolver.isUsableMac(PHONE_ONE));
    }

    @Test
    void aPlaceholderMacNeverBecomesAVendorName() {
        // Le defaut observe a l ecran : un noeud intitule « Appareil 00:00:00 »
        assertNull(NameResolver.vendorPrefix("00:00:00:00:00:00"));

        Identity identity = NameResolver.resolve("2001:db8::9", "00:00:00:00:00:00", false, Map.of());

        assertEquals("Appareil 9", identity.name(), "on retombe sur l adresse, pas sur un faux constructeur");
        assertFalse(identity.name().contains("00:00:00"));
    }

    @Test
    void placeholderMacsDoNotCollapseDistinctDevicesTogether() {
        // Trois appareils non resolus partagent la meme adresse factice : les fondre
        // en un seul noeud serait une perte d information, pas une deduplication.
        List<Device> merged = NetworkScanner.mergeByHardware(List.of(
                withMac(device("192.168.1.20"), "00:00:00:00:00:00"),
                withMac(device("192.168.1.21"), "00:00:00:00:00:00"),
                withMac(device("192.168.1.22"), "00:00:00:00:00:00")));

        assertEquals(3, merged.size());
    }

    private static Device device(String ip) {
        return new Device(ip, "Appareil", ip, DeviceType.LAN_PEER);
    }

    private static Device withMac(Device device, String mac) {
        device.setMacAddress(mac);
        return device;
    }
}
