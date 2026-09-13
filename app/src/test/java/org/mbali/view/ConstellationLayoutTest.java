package org.mbali.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mbali.model.AdapterKind;
import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkAdapter;
import org.mbali.model.NetworkLink;
import org.mbali.model.NetworkTopology;
import org.mbali.model.Peripheral;
import org.mbali.model.PeripheralKind;
import org.mbali.model.PortEndpoint;
import org.mbali.model.PortType;
import org.mbali.view.ConstellationLayout.Constellation;
import org.mbali.view.ConstellationLayout.Satellite;
import org.mbali.view.ConstellationLayout.SatelliteKind;
import org.mbali.view.ConstellationLayout.Star;

class ConstellationLayoutTest {

    private static Device localHost() {
        Device pc = new Device("local-host", "PC", "192.168.1.64", DeviceType.LOCAL_HOST);
        pc.addAdapter(new NetworkAdapter("wlan0", "Realtek Wi-Fi", AdapterKind.WIFI,
                "192.168.1.64", "AA:BB"));
        pc.addAdapter(new NetworkAdapter("vEthernet", "Hyper-V", AdapterKind.VIRTUAL,
                "172.25.160.1", "CC:DD"));
        pc.addPeripheral(new Peripheral("Game controller", PeripheralKind.CONTROLLER, "HID\\X"));
        pc.addEndpoint(new PortEndpoint(80, "HTTP", PortType.NETWORK_PHYSICAL, "System",
                "0.0.0.0", "HTTP/1.1 200 OK", true));
        pc.addEndpoint(new PortEndpoint(445, "TCP 445", PortType.NETWORK_PHYSICAL, "System"));
        return pc;
    }

    private static Device peer(String id) {
        return new Device(id, "Appareil", id, DeviceType.LAN_PEER);
    }

    private static Device gateway() {
        return new Device("192.168.1.254", "Box", "192.168.1.254", DeviceType.GATEWAY_ROUTER);
    }

    private static Star onlyStar(NetworkTopology topology) {
        return ConstellationLayout.compute(topology).stars().values().iterator().next();
    }

    /** Le montage réel : deux systèmes, la box avec ses clients, cette machine à côté. */
    private static NetworkTopology twoSystems(Device pc, Device box, Device... clients) {
        NetworkTopology topology = new NetworkTopology(pc);
        topology.addDevice(box);
        topology.addLink(new NetworkLink(box, pc, "Sortie reseau"));
        for (Device client : clients) {
            topology.addDevice(client);
            topology.addLink(new NetworkLink(box, client, "Voisin reseau"));
        }
        return topology;
    }

    @Test
    void theObserverIsItsOwnSystemBesideTheGateway() {
        // Le modele dit vrai : cette machine sort par la box. Mais elle n est pas
        // dessinee comme un satellite de la box — c est un systeme a part, comme elle.
        Device pc = localHost();
        Device box = gateway();
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box));

        assertTrue(map.stars().get(pc).isSystemRoot(), "cette machine est un systeme");
        assertTrue(map.stars().get(box).isSystemRoot(), "la box est un systeme");
        assertNull(map.stars().get(pc).parentId(), "elle ne gravite autour de personne");
        assertEquals(1, map.links().size(), "le lien reel reste dans le modele");
    }

    @Test
    void noDeviceHoldsTheOriginAnyMore() {
        // Il n y a plus de centre : les deux gros appareils sont de part et d autre.
        Device pc = localHost();
        Device box = gateway();
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box));

        for (Star star : map.stars().values()) {
            double rest = Math.hypot(ConstellationLayout.restX(star),
                    ConstellationLayout.restY(star));
            assertTrue(rest > 1_000,
                    star.device().getId() + " occupe le centre : " + rest);
        }
    }

    @Test
    void theTwoBigSystemsDanceWithoutEverApproaching() {
        Device pc = localHost();
        Device box = gateway();
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box, peer("192.168.1.31")));
        Star machine = map.stars().get(pc);
        Star hub = map.stars().get(box);

        double closest = Double.POSITIVE_INFINITY;
        double farthest = 0;
        for (double seconds = 0; seconds < 1_800; seconds += 0.31) {
            double distance = Math.hypot(
                    ConstellationLayout.starX(machine, seconds) - ConstellationLayout.starX(hub, seconds),
                    ConstellationLayout.starY(machine, seconds) - ConstellationLayout.starY(hub, seconds));
            closest = Math.min(closest, distance);
            farthest = Math.max(farthest, distance);
        }
        assertTrue(closest >= ConstellationLayout.SYSTEM_GAP,
                "les deux systemes se sont rapproches a " + closest);
        // La danse doit s entendre : une distance rigoureusement constante serait figee
        assertTrue(farthest - closest > 10_000,
                "les systemes ne bougent pas l un par rapport a l autre : " + (farthest - closest));
    }

    @Test
    void everySystemActuallyMoves() {
        Device pc = localHost();
        Device box = gateway();
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box));

        for (Star star : map.stars().values()) {
            assertTrue(star.drift() > 0, star.device().getId() + " est immobile");
            assertNotEquals(ConstellationLayout.starX(star, 0),
                    ConstellationLayout.starX(star, 40),
                    "figee dans le temps : " + star.device().getId());
        }
    }

    @Test
    void aClientKeepsFollowingItsGatewayWhileItDances() {
        // C est le coeur du changement : la position d un client est calculee a partir
        // de celle de sa box a l instant t, et non figee la ou elle etait au scan.
        Device pc = localHost();
        Device box = gateway();
        Device phone = peer("192.168.1.31");
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box, phone));
        Star hub = map.stars().get(box);
        Star mobile = map.stars().get(phone);

        assertEquals(box.getId(), mobile.parentId(), "le telephone tourne autour de la box");
        for (double seconds = 0; seconds < 900; seconds += 0.53) {
            double distance = Math.hypot(
                    ConstellationLayout.starX(mobile, seconds) - ConstellationLayout.starX(hub, seconds),
                    ConstellationLayout.starY(mobile, seconds) - ConstellationLayout.starY(hub, seconds));
            assertTrue(distance > 40_000 && distance < 110_000,
                    "le telephone a quitte l orbite de sa box a t=" + seconds + " : " + distance);
        }
    }

    @Test
    void aDeviceBehindAnotherOrbitsThatOneAndNotTheGateway() {
        // Un repeteur, et une TV qui passe par lui : la TV tourne autour du repeteur.
        Device pc = localHost();
        Device box = gateway();
        Device repeater = peer("192.168.1.40");
        Device tv = peer("192.168.1.41");
        NetworkTopology topology = twoSystems(pc, box, repeater);
        topology.addDevice(tv);
        topology.addLink(new NetworkLink(repeater, tv, "Voisin reseau"));

        Constellation map = ConstellationLayout.compute(topology);
        Star relay = map.stars().get(repeater);
        Star screen = map.stars().get(tv);

        assertEquals(repeater.getId(), screen.parentId());
        assertEquals(relay, screen.parent(), "la TV doit pointer sur le repeteur lui-meme");
        // Le decalage est desormais relatif au porteur : il se lit directement
        double offset = Math.hypot(screen.homeX(), screen.homeY());
        assertTrue(offset > 40_000 && offset < 100_000,
                "la TV gravite dans la bande de son repeteur : " + offset);
    }

    @Test
    void aDeviceWithNoKnownCarrierOrbitsNobody() {
        // Maintenant que l orbite vaut appartenance, faire tourner un appareil autour
        // d un autre sans preuve serait une affirmation fausse.
        Device pc = localHost();
        Device box = gateway();
        Device unknown = peer("192.168.1.77");
        NetworkTopology topology = twoSystems(pc, box);
        topology.addDevice(unknown);

        Constellation map = ConstellationLayout.compute(topology);
        Star lone = map.stars().get(unknown);

        assertNull(lone.parentId(), "aucune appartenance ne doit etre affirmee");
        assertNull(lone.parent());

        // L affirmation exacte est « au-dela des systemes », et non un seuil chiffre :
        // le champ exterieur est aplati de 0,74 en Y, donc un seuil en dur se trompe
        // d un quart selon l angle. On compare donc a la distance des systemes eux-memes.
        double loneDistance = Math.hypot(ConstellationLayout.restX(lone),
                ConstellationLayout.restY(lone));
        for (Star system : map.stars().values()) {
            if (system == lone) {
                continue;
            }
            double systemDistance = Math.hypot(ConstellationLayout.restX(system),
                    ConstellationLayout.restY(system));
            assertTrue(loneDistance > systemDistance,
                    "il doit deriver plus loin que " + system.device().getId()
                            + " : " + loneDistance + " contre " + systemDistance);
        }
    }

    @Test
    void severalSystemsSpreadOutInsteadOfCrowding() {
        // Trois systemes : l anneau doit s agrandir tout seul pour tenir l ecart.
        Device pc = localHost();
        Device box = gateway();
        Device otherRouter = peer("10.8.0.1");
        Device itsClient = peer("10.8.0.9");
        NetworkTopology topology = twoSystems(pc, box, peer("192.168.1.31"));
        topology.addDevice(otherRouter);
        topology.addDevice(itsClient);
        topology.addLink(new NetworkLink(otherRouter, itsClient, "Voisin reseau"));

        Constellation map = ConstellationLayout.compute(topology);
        List<Star> systems = new ArrayList<>();
        for (Star star : map.stars().values()) {
            if (star.isSystemRoot()) {
                systems.add(star);
            }
        }
        assertEquals(3, systems.size(), "cette machine, la box, et l autre routeur");

        for (double seconds = 0; seconds < 600; seconds += 0.47) {
            for (int i = 0; i < systems.size(); i++) {
                for (int j = i + 1; j < systems.size(); j++) {
                    double distance = Math.hypot(
                            ConstellationLayout.starX(systems.get(i), seconds)
                                    - ConstellationLayout.starX(systems.get(j), seconds),
                            ConstellationLayout.starY(systems.get(i), seconds)
                                    - ConstellationLayout.starY(systems.get(j), seconds));
                    assertTrue(distance >= ConstellationLayout.SYSTEM_GAP,
                            "deux systemes a " + distance + " a t=" + seconds);
                }
            }
        }
        assertEquals(otherRouter.getId(), map.stars().get(itsClient).parentId(),
                "son client reste dans son systeme, pas dans le notre");
    }

    @Test
    void systemsAreNotAlignedOnAnAxis() {
        // Defaut vu a l ecran : deux systemes tombaient a 0 et pi, soit un axe
        // horizontal parfait, avec le trait passant pile sur la croix centrale.
        Device pc = localHost();
        Device box = gateway();
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box));

        for (Star star : map.stars().values()) {
            double x = Math.abs(ConstellationLayout.restX(star));
            double y = Math.abs(ConstellationLayout.restY(star));
            assertTrue(x > 5_000 && y > 5_000,
                    star.device().getId() + " est pose sur un axe : " + x + " / " + y);
        }
    }

    @Test
    void devicesDoNotAllWeighTheSame() {
        // Si deux appareils ont la meme taille, la carte affirme qu ils pesent pareil.
        Device pc = localHost();
        Device box = gateway();
        Device bare = peer("192.168.1.31");
        Device rich = peer("192.168.1.32");
        for (int port = 0; port < 6; port++) {
            rich.addEndpoint(new PortEndpoint(80 + port, "TCP", PortType.NETWORK_PHYSICAL));
        }
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box, bare, rich));

        double bareSize = map.stars().get(bare).coreRadius();
        double richSize = map.stars().get(rich).coreRadius();
        assertTrue(richSize > bareSize,
                "celui dont on connait six ports doit peser plus : " + richSize + " contre " + bareSize);
        assertNotEquals(bareSize, richSize);
    }

    @Test
    void theGatewayIsHeavierThanAnyDeviceItCarries() {
        // « on dirait un appareil comme un autre » : le concentrateur doit dominer.
        Device pc = localHost();
        Device box = gateway();
        Device phone = peer("192.168.1.31");
        for (int port = 0; port < 4; port++) {
            phone.addEndpoint(new PortEndpoint(80 + port, "TCP", PortType.NETWORK_PHYSICAL));
        }
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box, phone));

        assertTrue(map.stars().get(box).coreRadius() > map.stars().get(phone).coreRadius(),
                "la box doit se distinguer de ses clients");
        // Une taille vraie, et non un symbole gonfle par le zoom. Le facteur est passe
        // de 2 a 1,3 deliberement : a 1 500 unites, un client ne faisait que 2 px et
        // devenait invisible ; il a grandi, et la box domine toujours clairement.
        assertTrue(map.stars().get(box).coreRadius()
                > map.stars().get(phone).coreRadius() * 1.3,
                "la box doit dominer par sa taille propre");
    }

    @Test
    void twoDistantDevicesAreNotLinkedTogether() {
        // Le lien de constellation naît de la proximite, et s eteint au-dela.
        assertEquals(1, ConstellationLayout.deviceLinkStrength(0), 1e-9);
        assertEquals(0, ConstellationLayout.deviceLinkStrength(ConstellationLayout.SYSTEM_GAP), 1e-9);
        assertTrue(ConstellationLayout.deviceLinkStrength(30_000)
                > ConstellationLayout.deviceLinkStrength(90_000));
    }

    @Test
    void aLinkAimsExactlyAtBothCentresAndStopsAtTheirEdges() {
        // Defaut vu a l'ecran : le trait entre la box et ce PC etait decale, parce qu'il
        // partait d'une carte reseau en orbite et non du noyau.
        double[] segment = ConstellationLayout.clipBetween(0, 0, 10, 100, 40, 20);

        // Les deux extremites sont sur la droite qui joint les centres
        assertEquals(0, segment[0] * 40 - segment[1] * 100, 1e-9);
        assertEquals(0, segment[2] * 40 - segment[3] * 100, 1e-9);
        // et chacune est exactement au bord de son corps
        assertEquals(10, Math.hypot(segment[0], segment[1]), 1e-9);
        assertEquals(20, Math.hypot(segment[2] - 100, segment[3] - 40), 1e-9);
    }

    @Test
    void twoOverlappingBodiesAreNotLinked() {
        assertNull(ConstellationLayout.clipBetween(0, 0, 30, 40, 0, 20),
                "deux corps qui se recouvrent n'ont pas de trait visible entre eux");
    }

    @Test
    void bluetoothDevicesOrbitTheMachineThatPairedThemBeyondItsCortege() {
        Device pc = localHost();
        Device box = gateway();
        Device earbuds = new Device("bt:112233445566", "TWS", "Bluetooth", DeviceType.BLUETOOTH);
        NetworkTopology topology = twoSystems(pc, box);
        topology.addDevice(earbuds);
        topology.addLink(new NetworkLink(pc, earbuds, "Bluetooth"));

        Constellation map = ConstellationLayout.compute(topology);
        Star machine = map.stars().get(pc);
        Star paired = map.stars().get(earbuds);

        assertEquals(pc.getId(), paired.parentId(), "appaires a ce PC, pas a la box");
        assertTrue(machine.isSystemRoot(), "ce PC reste un systeme a part entiere");
        for (double seconds = 0; seconds < 900; seconds += 0.61) {
            double distance = Math.hypot(
                    ConstellationLayout.starX(paired, seconds) - ConstellationLayout.starX(machine, seconds),
                    ConstellationLayout.starY(paired, seconds) - ConstellationLayout.starY(machine, seconds));
            // Au-dela de tout le cortege de ce PC, sans s'en eloigner demesurement. Les bornes
            // etaient chiffrees (50 000 - 105 000) quand le cortege tenait dans une bande fixe ;
            // il est desormais range sur des anneaux, et c'est lui qui fixe ou commence l'orbite.
            assertTrue(distance > machine.systemRadius() && distance < machine.systemRadius() + 90_000,
                    "les ecouteurs ont quitte l'orbite de ce PC a t=" + seconds + " : " + distance);
        }
    }

    @Test
    void aCycleNeverLeavesADeviceWithoutAPlace() {
        // Deux appareils qui se designent l un l autre n ont aucune racine : sans
        // garde-fou, ils resteraient introuvables sur la carte.
        Device pc = localHost();
        Device first = peer("192.168.1.50");
        Device second = peer("192.168.1.51");
        NetworkTopology topology = new NetworkTopology(pc);
        topology.addDevice(first);
        topology.addDevice(second);
        topology.addLink(new NetworkLink(first, second, "Voisin reseau"));
        topology.addLink(new NetworkLink(second, first, "Voisin reseau"));

        Constellation map = ConstellationLayout.compute(topology);

        assertEquals(3, map.stars().size(), "aucun appareil ne doit disparaitre");
        for (Star star : map.stars().values()) {
            assertTrue(Double.isFinite(ConstellationLayout.restX(star))
                            && Double.isFinite(ConstellationLayout.restY(star)),
                    "position indefinie pour " + star.device().getId());
        }
    }

    @Test
    void noObjectEverEntersTheCoreOfItsDevice() {
        Star star = onlyStar(new NetworkTopology(localHost()));
        double floor = ConstellationLayout.exclusionRadius(star);

        assertTrue(floor > star.coreRadius(), "la barriere doit depasser le noyau");
        // On echantillonne largement : la derive ne doit jamais franchir la limite
        for (double seconds = 0; seconds < 600; seconds += 0.37) {
            double coreX = ConstellationLayout.starX(star, seconds);
            double coreY = ConstellationLayout.starY(star, seconds);
            for (Satellite node : star.satellites()) {
                double[] point = ConstellationLayout.position(star, node, seconds);
                double distance = Math.hypot(point[0] - coreX, point[1] - coreY);
                assertTrue(distance >= floor - 1e-6,
                        node.label() + " a traverse le noyau a t=" + seconds
                                + " (distance " + distance + " < " + floor + ")");
            }
        }
    }

    @Test
    void theBarrierPushesOutwardWithoutTeleporting() {
        Star star = onlyStar(new NetworkTopology(localHost()));
        Satellite node = star.satellites().get(0);

        // La position reste continue : deux instants proches donnent deux points proches
        double[] a = ConstellationLayout.position(star, node, 12.00);
        double[] b = ConstellationLayout.position(star, node, 12.05);
        assertTrue(Math.hypot(b[0] - a[0], b[1] - a[1]) < 60,
                "un saut brutal signalerait une barriere mal appliquee");
    }

    @Test
    void devicesAreScatteredNotAlignedOnOneRing() {
        Device pc = localHost();
        Device box = gateway();
        Device[] clients = new Device[10];
        for (int i = 0; i < clients.length; i++) {
            clients[i] = peer("192.168.1." + (i + 2));
        }
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box, clients));

        Set<Long> radii = new HashSet<>();
        for (Device client : clients) {
            Star star = map.stars().get(client);
            radii.add(Math.round(Math.hypot(star.homeX(), star.homeY()) / 100));
        }
        assertEquals(10, radii.size(), "les appareils ne doivent pas partager un rayon");
    }

    @Test
    void everyObjectDriftsOnTwoDistinctFrequencies() {
        Star star = onlyStar(new NetworkTopology(localHost()));

        for (Satellite node : star.satellites()) {
            assertNotEquals(node.freqX(), node.freqY(),
                    "des frequences egales redonneraient une trajectoire reguliere : " + node.label());
            assertTrue(node.driftX() > 0 && node.driftY() > 0, "objet immobile : " + node.label());
            assertNotEquals(ConstellationLayout.satelliteX(star, node, 0),
                    ConstellationLayout.satelliteX(star, node, 13),
                    "immobile dans le temps : " + node.label());
        }
    }

    @Test
    void objectsOfOneFamilyDoNotSitAtTheSameDistance() {
        Star star = onlyStar(new NetworkTopology(localHost()));

        Set<Long> distances = new HashSet<>();
        for (Satellite node : star.satellites()) {
            if (node.kind() == SatelliteKind.ADAPTER) {
                distances.add(Math.round(Math.hypot(node.homeX(), node.homeY())));
            }
        }
        assertEquals(2, distances.size(), "deux cartes reseau, deux distances differentes");
    }

    @Test
    void theAdapterCarryingTheDeviceAddressIsTheExit() {
        Star star = onlyStar(new NetworkTopology(localHost()));

        Satellite exit = star.byId().get(star.primaryAdapterId());
        assertEquals(SatelliteKind.ADAPTER, exit.kind());
        assertTrue(exit.detail().contains("192.168.1.64"),
                "la sortie doit etre la carte qui porte l adresse de la machine");
    }

    @Test
    void aLinkFadesInWhenCloseAndVanishesAtTheThreshold() {
        double reach = ConstellationLayout.linkReach(SatelliteKind.PORT);

        assertEquals(1, ConstellationLayout.linkStrength(SatelliteKind.PORT, 0), 1e-9);
        assertEquals(0, ConstellationLayout.linkStrength(SatelliteKind.PORT, reach), 1e-9);
        assertEquals(0, ConstellationLayout.linkStrength(SatelliteKind.PORT, reach * 2), 1e-9);

        double near = ConstellationLayout.linkStrength(SatelliteKind.PORT, reach * .25);
        double far = ConstellationLayout.linkStrength(SatelliteKind.PORT, reach * .75);
        assertTrue(near > far, "le lien doit faiblir avec la distance");
    }

    @Test
    void aServiceStaysInTheNeighbourhoodOfItsOwnPort() {
        Star star = onlyStar(new NetworkTopology(localHost()));

        Satellite service = star.satellites().stream()
                .filter(node -> node.kind() == SatelliteKind.SERVICE).findFirst().orElseThrow();
        Satellite port = star.byId().get(service.parentId());
        assertEquals(SatelliteKind.PORT, port.kind());

        for (double seconds : new double[] { 0, 5.5, 120 }) {
            double[] a = ConstellationLayout.position(star, service, seconds);
            double[] b = ConstellationLayout.position(star, port, seconds);
            assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) < 8_000,
                    "un service ne doit pas quitter le voisinage de son port");
        }
    }

    @Test
    void placementIsDeterministicSoTheMapNeverJumps() {
        Device pc = localHost();
        Device box = gateway();
        Device existing = peer("192.168.1.90");

        Star first = ConstellationLayout.compute(twoSystems(pc, box, existing))
                .stars().get(existing);
        Star second = ConstellationLayout.compute(twoSystems(pc, box, peer("192.168.1.2"), existing))
                .stars().get(existing);

        assertEquals(first.homeX(), second.homeX());
        assertEquals(first.homeY(), second.homeY());
        assertEquals(first.drift(), second.drift());
    }

    @Test
    void theModelKeepsItsRelationsEvenWithoutADrawnLine() {
        Device pc = localHost();
        Device other = peer("192.168.1.20");
        NetworkTopology topology = new NetworkTopology(pc);
        topology.addDevice(other);
        topology.addLink(new NetworkLink(pc, other, "Voisin reseau"));

        Constellation map = ConstellationLayout.compute(topology);

        assertEquals(1, map.links().size());
        assertTrue(Math.hypot(map.stars().get(other).homeX(),
                map.stars().get(other).homeY()) > 40_000, "l espace doit rester vaste");
    }

    @Test
    void aCrowdedCortegeNeverOverlapsItself() {
        // « les objets autour de l'appareil se marchent dessus » : cinquante ports, leurs
        // services, douze peripheriques et quatre cartes ne doivent jamais se toucher.
        Device pc = localHost();
        for (int i = 0; i < 10; i++) {
            pc.addPeripheral(new Peripheral("Materiel " + i, PeripheralKind.USB, "USB-X" + i));
        }
        pc.addAdapter(new NetworkAdapter("eth1", "Ethernet", AdapterKind.ETHERNET, "10.0.0.2", "EE:FF"));
        pc.addAdapter(new NetworkAdapter("vpn0", "VPN", AdapterKind.VIRTUAL, "10.9.0.2", "11:22"));
        for (int port = 0; port < 48; port++) {
            pc.addEndpoint(new PortEndpoint(5000 + port, "TCP", PortType.NETWORK_PHYSICAL, "svc" + port));
        }
        Star star = onlyStar(new NetworkTopology(pc));
        List<Satellite> nodes = star.satellites();
        assertEquals(4 + 11 + 50 * 2, nodes.size());

        for (double seconds = 0; seconds < 1_200; seconds += 7.3) {
            double[][] points = new double[nodes.size()][];
            for (int i = 0; i < nodes.size(); i++) {
                points[i] = ConstellationLayout.position(star, nodes.get(i), seconds);
            }
            for (int i = 0; i < nodes.size(); i++) {
                double ri = ConstellationLayout.satelliteRadius(nodes.get(i).kind());
                for (int j = i + 1; j < nodes.size(); j++) {
                    double rj = ConstellationLayout.satelliteRadius(nodes.get(j).kind());
                    double distance = Math.hypot(points[i][0] - points[j][0], points[i][1] - points[j][1]);
                    assertTrue(distance > ri + rj,
                            nodes.get(i).label() + " touche " + nodes.get(j).label() + " a t=" + seconds);
                }
            }
        }
    }

    @Test
    void thirtyClientsOfOneGatewayNeverOverlap() {
        Device pc = localHost();
        Device box = gateway();
        Device[] clients = new Device[30];
        for (int i = 0; i < clients.length; i++) {
            clients[i] = peer("192.168.1." + (i + 2));
            for (int port = 0; port < i % 5; port++) {
                clients[i].addEndpoint(new PortEndpoint(80 + port, "TCP", PortType.NETWORK_PHYSICAL));
            }
        }
        Constellation map = ConstellationLayout.compute(twoSystems(pc, box, clients));

        for (double seconds = 0; seconds < 900; seconds += 11.1) {
            for (int i = 0; i < clients.length; i++) {
                Star a = map.stars().get(clients[i]);
                for (int j = i + 1; j < clients.length; j++) {
                    Star b = map.stars().get(clients[j]);
                    double distance = Math.hypot(
                            ConstellationLayout.starX(a, seconds) - ConstellationLayout.starX(b, seconds),
                            ConstellationLayout.starY(a, seconds) - ConstellationLayout.starY(b, seconds));
                    // 1,6 rayon : les piques comprises
                    assertTrue(distance > 1.6 * (a.coreRadius() + b.coreRadius()),
                            clients[i].getId() + " touche " + clients[j].getId() + " a t=" + seconds);
                }
            }
        }
    }
}
