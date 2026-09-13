package org.mbali.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkAdapter;
import org.mbali.model.NetworkLink;
import org.mbali.model.NetworkTopology;
import org.mbali.model.Peripheral;
import org.mbali.model.PortEndpoint;

/**
 * Un espace, et non une horlogerie.
 *
 * La carte n'a pas de centre. Elle est faite de systèmes indépendants : la box en est
 * un, cette machine en est un autre, et aucun des deux ne tient l'origine. Chaque
 * système emporte ce qui dépend de lui — les appareils du réseau autour de la box,
 * les ports et le matériel autour de cette machine — de la même manière et à tous les
 * niveaux : dès qu'on découvre qu'un appareil en connecte un autre, cet autre se met
 * à graviter autour de lui.
 *
 * L'orbite EST le lien. Un objet qui tourne autour de son appareil n'a pas besoin
 * d'un trait vers lui : l'appartenance se lit dans le mouvement. Seuls deux systèmes
 * distincts sont reliés par un trait, car là l'orbite ne dit rien.
 *
 * Rien ne se marche dessus, et ce n'est pas réglé à l'œil : le cortège d'un appareil est
 * rangé sur des anneaux dont l'écart et le nombre de places sont calculés à partir de
 * l'encombrement de chaque objet, dérive comprise. Les appareils qui gravitent autour
 * d'un autre sont rangés de la même manière, au-delà de son cortège, et l'écart entre
 * deux systèmes grandit avec leur étendue réelle.
 *
 * Qui dépend de qui n'est pas décidé ici : c'est déduit des liens du modèle. La carte
 * ne peut donc pas affirmer une topologie que le modèle ne dit pas. Un appareil dont
 * on ignore le rattachement ne gravite autour de personne : il dérive dans le champ
 * extérieur, car le faire tourner autour d'un appareil serait affirmer sans preuve.
 *
 * Le placement reste déterministe : un appareil retrouve sa place d'un scan au
 * suivant, car tout est tiré de son identifiant et de l'ordre de ses voisins.
 */
public final class ConstellationLayout {

    public enum SatelliteKind { ADAPTER, PERIPHERAL, PORT, SERVICE }

    /**
     * Un objet du cortège. Il tourne sur son anneau (orbit, angle, spin) et dérive
     * légèrement autour de sa place, sur deux fréquences distinctes. homeX et homeY
     * donnent sa place à l'instant zéro, relative à son centre.
     */
    public record Satellite(String id, String parentId, String label, String detail,
                            SatelliteKind kind, double homeX, double homeY,
                            double driftX, double driftY,
                            double freqX, double freqY, double phaseX, double phaseY,
                            double orbit, double angle, double spin) {
    }

    /**
     * Un appareil et son cortège.
     *
     * parent est l'appareil autour duquel celui-ci gravite, ou null s'il est la racine
     * de son système. homeX et homeY sont un DÉCALAGE par rapport à ce parent, et non
     * une position absolue : c'est ce qui permet à un client de suivre sa box pendant
     * qu'elle dérive. rings donne le rayon de chaque anneau du cortège.
     */
    public record Star(Device device, Star parent, String parentId, double homeX, double homeY,
                       double drift, double freqX, double freqY, double phaseX, double phaseY,
                       double coreRadius, String primaryAdapterId,
                       List<Satellite> satellites, Map<String, Satellite> byId,
                       double systemRadius, List<Double> rings) {

        /** Vrai si cet appareil ne gravite autour d'aucun autre. */
        public boolean isSystemRoot() {
            return parent == null;
        }
    }

    public record Constellation(Map<Device, Star> stars, List<NetworkLink> links) {
        public double totalRadius() {
            double radius = 1;
            for (Star star : stars.values()) {
                radius = Math.max(radius, Math.hypot(restX(star), restY(star))
                        + driftReach(star) + star.systemRadius());
            }
            return radius;
        }

        public Star starForId(String id) {
            return stars.values().stream()
                    .filter(star -> star.device().getId().equals(id))
                    .findFirst().orElse(null);
        }
    }

    /** Rayons vrais des objets du cortège, en unités du monde. */
    public static final double ADAPTER_RADIUS = 3_000;
    public static final double PERIPHERAL_RADIUS = 2_600;
    public static final double PORT_RADIUS = 2_200;
    public static final double SERVICE_RADIUS = 1_100;

    /** Dérive maximale d'un objet du cortège autour de sa place, et d'un service. */
    private static final double SATELLITE_DRIFT = 500;
    private static final double SERVICE_DRIFT = 120;
    /** Un service tourne autour de son port, sans jamais toucher sa sphère. */
    private static final double SERVICE_ORBIT = PORT_RADIUS + SERVICE_RADIUS + SERVICE_DRIFT + 900;

    /** Vide garanti entre deux objets voisins du cortège, au pire de leur dérive. */
    private static final double SATELLITE_GAP = 1_800;
    /** Dispersion radiale à l'intérieur d'un anneau : un anneau parfait se lit comme un dessin imposé. */
    private static final double SATELLITE_JITTER = 900;
    /** Le cortège commence à cette distance du centre, en plus gros noyau possible : l'appareil garde de l'espace. */
    private static final double CORTEGE_START = 1.9;

    /**
     * Encombrement visible d'un appareil, en rayons de noyau : ses piques partent jusqu'à
     * 1,55 fois son rayon.
     */
    private static final double DEVICE_ENVELOPE = 1.6;
    private static final double DEVICE_GAP = 2_000;

    /**
     * Bandes des appareils qui gravitent autour d'un autre : { début minimal, dispersion
     * radiale }. Le début réel recule au-delà du cortège du porteur quand celui-ci est vaste.
     */
    private static final double[] GATEWAY_BAND = { 30_000, 6_000 };
    private static final double[] LAN_BAND = { 60_000, 10_000 };
    private static final double[] REMOTE_BAND = { 112_000, 12_000 };
    /** Les appareils Bluetooth gravitent autour de la machine qui les a appairés, au-delà de son cortège. */
    private static final double[] BLUETOOTH_BAND = { 78_000, 4_000 };

    /** Aplatissement vertical des orbites des appareils. */
    private static final double SQUASH = 0.74;

    /**
     * Écart minimal garanti entre deux systèmes, quelle que soit la dérive. C'est un
     * plancher : quand deux systèmes sont plus étendus, l'écart réel grandit avec eux.
     */
    public static final double SYSTEM_GAP = 260_000;

    /** Amplitude de la danse d'un gros appareil. */
    public static final double ROOT_DRIFT = 14_000;

    /**
     * Dispersion des systèmes, en fraction du secteur qui leur revient, et en fraction
     * du rayon de l'anneau. Sans elle, deux systèmes tombaient à 0 et π : un axe
     * horizontal parfait, que l'œil lit immédiatement comme une figure imposée.
     */
    private static final double ROOT_JITTER = 0.34;
    private static final double ROOT_RADIAL_SPREAD = 0.08;

    /** L'anneau n'est pas posé sur les axes : rien ne doit paraître aligné. */
    private static final double ROOT_PHASE = 0.37;

    /** Distance en deçà de laquelle deux appareils se relient en constellation. */
    private static final double DEVICE_REACH = 120_000;

    /** Où dérive un appareil dont on ignore le rattachement, au-delà des systèmes. */
    private static final double[] OUTER_FIELD = { 180_000, 260_000 };

    /** Distance en deçà de laquelle deux objets de même nature se relient. */
    private static final double ADAPTER_REACH = 15_000;
    private static final double PERIPHERAL_REACH = 15_000;
    private static final double PORT_REACH = 14_000;
    private static final double SERVICE_REACH = 6_000;

    /** Marge autour du noyau, exprimée en rayons de noyau. */
    private static final double EXCLUSION_FACTOR = 1.9;

    private ConstellationLayout() {
    }

    public static Constellation compute(NetworkTopology topology) {
        List<Device> devices = topology.getDevices().stream()
                .sorted(Comparator.comparing(Device::getId)).toList();
        if (devices.isEmpty()) {
            return new Constellation(Map.of(), List.copyOf(topology.getLinks()));
        }
        Map<String, Device> byId = new LinkedHashMap<>();
        devices.forEach(device -> byId.put(device.getId(), device));

        Device observer = topology.getLocalHost() != null
                && byId.containsKey(topology.getLocalHost().getId())
                        ? topology.getLocalHost() : devices.get(0);

        Map<String, String> uplinks = uplinks(topology, byId);

        // Un appareil n'est un système que si quelque chose dépend de lui — cette
        // machine comprise. On le relève AVANT de détacher l'observateur : sur un
        // réseau où la box ne porte que ce PC, détacher l'observateur effaçait la
        // seule preuve que la box concentre quoi que ce soit.
        Set<String> carriesOthers = new LinkedHashSet<>(uplinks.values());

        // La machine depuis laquelle on observe n'est le satellite de personne : elle
        // forme un système à part entière, à côté de la box. Le modèle conserve le
        // lien réel entre les deux ; c'est le rendu qui le trace d'un système à l'autre.
        uplinks.remove(observer.getId());

        List<Device> roots = new ArrayList<>();
        List<Device> adrift = new ArrayList<>();
        for (Device device : devices) {
            if (uplinks.containsKey(device.getId())) {
                continue;
            }
            if (device.equals(observer) || carriesOthers.contains(device.getId())) {
                roots.add(device);
            } else {
                adrift.add(device);
            }
        }

        // Le cortège de chaque appareil ne dépend que de lui : on le range d'abord, pour
        // savoir jusqu'où il s'étend avant d'y poser les appareils qui gravitent autour.
        Map<String, Cortege> corteges = new LinkedHashMap<>();
        for (Device device : devices) {
            corteges.put(device.getId(), cortege(device));
        }

        Map<String, double[]> offsets = new LinkedHashMap<>();
        Map<String, List<Device>> carried = new LinkedHashMap<>();
        for (Device device : devices) {
            String carrier = uplinks.get(device.getId());
            if (carrier != null) {
                carried.computeIfAbsent(carrier, key -> new ArrayList<>()).add(device);
            }
        }
        carried.forEach((carrier, members) -> placeMembers(corteges.get(carrier).reach(), members, offsets));

        // L'écart entre systèmes suit leur étendue réelle, cortège et appareils compris
        double[] extents = roots.stream()
                .mapToDouble(root -> extent(root.getId(), corteges, carried, offsets))
                .sorted().toArray();
        double gap = SYSTEM_GAP;
        if (extents.length >= 2) {
            double needed = extents[extents.length - 1] + extents[extents.length - 2] + 30_000;
            // Par paliers de 80 000 : une découverte qui agrandit à peine un système ne déplace pas la carte
            gap = Math.max(gap, Math.ceil(needed / 80_000) * 80_000);
        }
        double ring = rootRing(roots.size(), gap);
        for (int index = 0; index < roots.size(); index++) {
            offsets.put(roots.get(index).getId(),
                    rootSlot(roots.get(index), index, roots.size(), ring));
        }
        for (Device lost : adrift) {
            offsets.put(lost.getId(), outerField(lost, ring));
        }

        // Un parent doit exister avant son enfant, puisque l'enfant le référence.
        Map<String, Star> built = new LinkedHashMap<>();
        List<Device> pending = new ArrayList<>(devices);
        boolean placedSomething = true;
        while (placedSomething) {
            placedSomething = false;
            Iterator<Device> waiting = pending.iterator();
            while (waiting.hasNext()) {
                Device device = waiting.next();
                String parentId = uplinks.get(device.getId());
                if (parentId != null && !built.containsKey(parentId)) {
                    continue;
                }
                double[] offset = offsets.get(device.getId());
                built.put(device.getId(), star(device, built.get(parentId), offset, corteges.get(device.getId())));
                waiting.remove();
                placedSomething = true;
            }
        }
        // Filet de sécurité : plutôt que de disparaître, un appareil dont le parent
        // reste introuvable est posé sans parent.
        for (Device stranded : pending) {
            double[] offset = offsets.getOrDefault(stranded.getId(), outerField(stranded, ring));
            built.put(stranded.getId(), star(stranded, null, offset, corteges.get(stranded.getId())));
        }

        Map<Device, Star> stars = new LinkedHashMap<>();
        for (Device device : devices) {
            stars.put(device, built.get(device.getId()));
        }
        return new Constellation(Collections.unmodifiableMap(stars), List.copyOf(topology.getLinks()));
    }

    /**
     * Qui dépend de qui, lu dans les liens du modèle : le porteur d'un appareil est la
     * source du lien qui le désigne.
     */
    private static Map<String, String> uplinks(NetworkTopology topology, Map<String, Device> byId) {
        Map<String, String> uplinks = new LinkedHashMap<>();
        for (NetworkLink link : topology.getLinks()) {
            if (link.source() == null || link.target() == null) {
                continue;
            }
            String child = link.target().getId();
            String parent = link.source().getId();
            if (child.equals(parent) || !byId.containsKey(parent) || !byId.containsKey(child)) {
                continue;
            }
            uplinks.putIfAbsent(child, parent);
        }
        // Un cycle laisserait tout un groupe d'appareils sans racine, donc sans place.
        List<String> looping = new ArrayList<>();
        for (String id : uplinks.keySet()) {
            if (loops(id, uplinks)) {
                looping.add(id);
            }
        }
        looping.forEach(uplinks.keySet()::remove);
        return uplinks;
    }

    /** Vrai si remonter les porteurs depuis cet appareil n'atteint aucune racine. */
    private static boolean loops(String start, Map<String, String> uplinks) {
        String climber = start;
        for (int step = 0; step <= uplinks.size(); step++) {
            climber = uplinks.get(climber);
            if (climber == null) {
                return false;
            }
            if (climber.equals(start)) {
                return true;
            }
        }
        return true;
    }

    /**
     * Rayon de l'anneau des systèmes, calculé et non choisi.
     *
     * La corde entre deux emplacements voisins vaut 2·R·sin(π/n). Au pire de la
     * dispersion angulaire (1 − ROOT_JITTER) et radiale (1 − SPREAD), elle doit dépasser
     * l'écart voulu plus les deux dérives. Le rayon s'en déduit, et s'agrandit donc tout
     * seul quand des systèmes s'ajoutent ou s'étendent.
     */
    private static double rootRing(int systems, double gap) {
        if (systems <= 1) {
            return 0;
        }
        double worstAngle = (Math.PI / systems) * (1 - ROOT_JITTER);
        double worstRadius = 1 - ROOT_RADIAL_SPREAD;
        return (gap + 2 * ROOT_DRIFT) / (2 * worstRadius * Math.sin(worstAngle));
    }

    /** Emplacement d'un système sur l'anneau, dispersé dans le secteur qui lui revient. */
    private static double[] rootSlot(Device device, int index, int systems, double ring) {
        if (systems <= 1) {
            return new double[] { 0, 0, ROOT_DRIFT };
        }
        Random random = new Random(device.getId().hashCode() * 2_246_822_519L + 7);
        double sector = 2 * Math.PI / systems;
        double angle = ROOT_PHASE + index * sector
                + (random.nextDouble() - 0.5) * sector * ROOT_JITTER;
        double radius = ring * (1 - ROOT_RADIAL_SPREAD
                + random.nextDouble() * ROOT_RADIAL_SPREAD * 2);
        return new double[] { Math.cos(angle) * radius, Math.sin(angle) * radius, ROOT_DRIFT };
    }

    /**
     * Range les appareils portés par un même porteur sur des anneaux aplatis, au-delà de
     * son cortège, par groupes : passerelles, réseau local, Bluetooth, serveurs distants.
     *
     * L'aplatissement de 0,74 rapproche deux points au plus d'un facteur 0,74 : les écarts
     * sont donc calculés sur le cercle, divisés par 0,74, ce qui garantit l'écart réel sur
     * l'ellipse. L'écart entre deux anneaux est celui du plus gros appareil possible de la
     * bande, pour qu'un appareil qui grossit ne déplace pas les anneaux.
     */
    private static void placeMembers(double carrierReach, List<Device> members, Map<String, double[]> offsets) {
        // Bord extérieur du groupe précédent, en rayon du cercle avant aplatissement
        double outer = carrierReach / SQUASH;
        for (double[] band : new double[][] { GATEWAY_BAND, LAN_BAND, BLUETOOTH_BAND, REMOTE_BAND }) {
            List<Device> group = members.stream().filter(device -> bandFor(device.getType()) == band)
                    .sorted(Comparator.comparing(Device::getId)).toList();
            if (group.isEmpty()) {
                continue;
            }
            double widest = maxCoreRadius(group.get(0).getType()) * DEVICE_ENVELOPE + MEMBER_DRIFT_MAX;
            double jitter = band[1];
            double centre = jitter / 2 + Math.max(band[0], outer + (widest + DEVICE_GAP) / SQUASH);
            Orbits orbits = new Orbits(centre, (2 * widest + DEVICE_GAP) / SQUASH + jitter, jitter, SQUASH);
            for (Device device : group) {
                double footprint = coreRadiusFor(device) * DEVICE_ENVELOPE + memberDrift(device);
                double[] slot = orbits.claim(device.getId().hashCode(), footprint, DEVICE_GAP);
                offsets.put(device.getId(), new double[] {
                        Math.cos(slot[1]) * slot[0], Math.sin(slot[1]) * slot[0] * SQUASH, memberDrift(device) });
            }
            outer = orbits.outermost() + jitter / 2 + widest / SQUASH;
        }
    }

    /** Amplitude de la danse d'un appareil qui gravite autour d'un autre, tirée de son identifiant. */
    private static double memberDrift(Device device) {
        return 1_500 + Math.floorMod(device.getId().hashCode() * 2_654_435_761L, 1_000L) * 1.5;
    }

    private static final double MEMBER_DRIFT_MAX = 3_000;

    /**
     * Étendue d'un système depuis son centre : son cortège, et chaque appareil qui gravite
     * autour de lui avec sa propre étendue.
     */
    private static double extent(String id, Map<String, Cortege> corteges, Map<String, List<Device>> carried,
                                 Map<String, double[]> offsets) {
        double reach = corteges.get(id).reach();
        for (Device member : carried.getOrDefault(id, List.of())) {
            double[] offset = offsets.get(member.getId());
            reach = Math.max(reach, Math.hypot(offset[0], offset[1] / SQUASH) + offset[2]
                    + Math.max(coreRadiusFor(member) * DEVICE_ENVELOPE,
                            extent(member.getId(), corteges, carried, offsets)));
        }
        return reach;
    }

    /**
     * Un appareil dont on ignore le rattachement dérive au-delà des systèmes. Le faire
     * tourner autour de l'un d'eux affirmerait une appartenance qu'on n'a pas établie.
     */
    private static double[] outerField(Device device, double ring) {
        Random random = new Random(device.getId().hashCode() * 7_919L + 31);
        double radius = ring + OUTER_FIELD[0]
                + random.nextDouble() * (OUTER_FIELD[1] - OUTER_FIELD[0]);
        double angle = random.nextDouble() * Math.PI * 2;
        return new double[] {
                Math.cos(angle) * radius,
                Math.sin(angle) * radius * SQUASH,
                2_400 + random.nextDouble() * 2_600 };
    }

    /** Le cortège rangé d'un appareil : ses objets, ses anneaux, et jusqu'où il s'étend. */
    private record Cortege(List<Satellite> satellites, List<Double> rings, String primaryAdapterId, double reach) {
    }

    /**
     * Range le cortège sur des anneaux : cartes réseau au plus près, puis matériel, puis
     * ports, chacun avec son service qui lui tourne autour.
     *
     * Le cortège commence à 1,9 fois le plus gros noyau possible de l'appareil, et non son
     * noyau du moment : un port qui apparaît fait grossir l'appareil, mais ne doit pas
     * repousser tous les anneaux.
     */
    private static Cortege cortege(Device device) {
        Random random = new Random(device.getId().hashCode() * 40_503L + 17);
        long seed = device.getId().hashCode();
        List<Satellite> nodes = new ArrayList<>();
        List<Double> rings = new ArrayList<>();

        String primaryAdapterId = null;
        List<String[]> adapters = new ArrayList<>();
        for (NetworkAdapter adapter : device.getAdapters()) {
            String id = "adapter:" + adapter.name() + ':' + adapter.ipAddress();
            adapters.add(new String[] { id, adapter.kind().getLabel(), adapter.displayName() + " · " + adapter.ipAddress() });
            // La carte qui porte l'adresse de l'appareil est celle par laquelle il atteint le réseau
            if (primaryAdapterId == null && adapter.ipAddress().equals(device.getIpAddress())) {
                primaryAdapterId = id;
            }
        }
        if (primaryAdapterId == null && !adapters.isEmpty()) {
            primaryAdapterId = adapters.get(0)[0];
        }
        List<String[]> peripherals = new ArrayList<>();
        for (Peripheral peripheral : device.getPeripherals()) {
            peripherals.add(new String[] { "peripheral:" + peripheral.hardwareId(), peripheral.name(), peripheral.kind().getLabel() });
        }

        double edge = maxCoreRadius(device.getType()) * CORTEGE_START;
        edge = ring(nodes, rings, adapters, SatelliteKind.ADAPTER, ADAPTER_RADIUS + SATELLITE_DRIFT, edge, random, seed);
        edge = ring(nodes, rings, peripherals, SatelliteKind.PERIPHERAL, PERIPHERAL_RADIUS + SATELLITE_DRIFT, edge, random, seed + 1);

        List<PortEndpoint> ports = device.getEndpoints();
        if (!ports.isEmpty()) {
            // Un port et son service forment une seule place : le service tourne à l'intérieur
            double footprint = Math.max(PORT_RADIUS, SERVICE_ORBIT + SERVICE_RADIUS + SERVICE_DRIFT) + SATELLITE_DRIFT;
            Orbits orbits = cortegeOrbits(edge, footprint);
            for (PortEndpoint port : ports) {
                String portId = "port:" + port.getPortNumber() + ':' + port.getLocalAddress() + ':' + port.getOwner();
                String detail = port.getOwner() + (port.getLocalAddress().isBlank() ? "" : " · " + port.getLocalAddress());
                double[] slot = orbits.claim(portId.hashCode(), footprint, SATELLITE_GAP);
                nodes.add(satellite(random, portId, null, "Port " + port.getPortNumber(), detail,
                        SatelliteKind.PORT, slot[0], slot[1], spin(slot[2], seed), SATELLITE_DRIFT));
                String evidence = port.getServiceEvidence().isBlank()
                        ? (port.isServiceVerified() ? "Service confirmé" : "Attribution indicative")
                        : port.getServiceEvidence();
                nodes.add(satellite(random, portId + ":service", portId, port.getName(), evidence,
                        SatelliteKind.SERVICE, SERVICE_ORBIT, Math.floorMod(portId.hashCode(), 628) / 100.0,
                        .05 * ((portId.hashCode() & 1) == 0 ? 1 : -1), SERVICE_DRIFT));
            }
            rings.addAll(orbits.used());
            edge = orbits.outermost() + SATELLITE_JITTER / 2 + footprint;
        }
        double reach = nodes.isEmpty() ? coreRadiusFor(device) * DEVICE_ENVELOPE : edge;
        return new Cortege(List.copyOf(nodes), List.copyOf(rings), primaryAdapterId, reach);
    }

    /** Range une nature d'objets sur ses anneaux, à partir du bord laissé par la précédente. */
    private static double ring(List<Satellite> nodes, List<Double> rings, List<String[]> items, SatelliteKind kind,
                               double footprint, double edge, Random random, long seed) {
        if (items.isEmpty()) {
            return edge;
        }
        Orbits orbits = cortegeOrbits(edge, footprint);
        for (String[] item : items) {
            double[] slot = orbits.claim(item[0].hashCode(), footprint, SATELLITE_GAP);
            nodes.add(satellite(random, item[0], null, item[1], item[2], kind, slot[0], slot[1],
                    spin(slot[2], seed), SATELLITE_DRIFT));
        }
        rings.addAll(orbits.used());
        return orbits.outermost() + SATELLITE_JITTER / 2 + footprint;
    }

    private static Orbits cortegeOrbits(double edge, double footprint) {
        return new Orbits(edge + SATELLITE_GAP + footprint + SATELLITE_JITTER / 2,
                2 * footprint + SATELLITE_GAP + SATELLITE_JITTER, SATELLITE_JITTER, 1);
    }

    /**
     * Des anneaux à places réservées.
     *
     * Chaque anneau est découpé en places égales. Un objet d'encombrement f (corps et dérive
     * compris) a besoin d'un demi-angle α = (f + écart/2) · π / (2·r), où r est le rayon
     * intérieur de l'anneau, aplatissement compris ; il réserve assez de places consécutives
     * pour couvrir 2α. Deux objets qui ne partagent aucune place sont séparés d'un angle
     * θ ≥ αa + αb, et comme une corde vaut au moins 2θr/π pour θ ≤ π, leurs centres restent à
     * fa + fb + écart. D'un anneau au suivant, le rayon avance du double du plus gros
     * encombrement plus l'écart : l'écart radial seul suffit alors, quels que soient les
     * angles, et les anneaux peuvent tourner à des vitesses différentes.
     *
     * Chaque objet réclame d'abord la place tirée de son identifiant, puis les places voisines
     * de part et d'autre, puis l'anneau suivant : un nouvel objet ne déplace un objet déjà
     * posé que s'il lui prend exactement sa place.
     */
    private static final class Orbits {
        private static final double PLACE = 600;
        private final double first;
        private final double step;
        private final double jitter;
        private final double squash;
        private final List<boolean[]> taken = new ArrayList<>();

        Orbits(double first, double step, double jitter, double squash) {
            this.first = first;
            this.step = step;
            this.jitter = jitter;
            this.squash = squash;
        }

        /** @return { rayon, angle, rayon de l'anneau } */
        double[] claim(long hash, double footprint, double gap) {
            long mixed = hash * 0x9E3779B97F4A7C15L;
            double shake = ((mixed >>> 20) & 0x3FF) / 1023.0 - .5;
            for (int ring = 0; ; ring++) {
                double centre = first + ring * step;
                double inner = (centre - jitter / 2) * squash;
                while (taken.size() <= ring) {
                    int places = Math.max(8, (int) Math.floor(Math.PI * 2 * (first + taken.size() * step - jitter / 2) * squash / PLACE));
                    taken.add(new boolean[places]);
                }
                boolean[] places = taken.get(ring);
                int count = places.length;
                double width = Math.PI * 2 / count;
                double half = (footprint + gap / 2) * Math.PI / (2 * inner);
                int needed = half >= Math.PI / 2 ? count : Math.min(count, (int) Math.ceil(2 * half / width));
                int preferred = (int) Math.floorMod(mixed >>> 32, (long) count);
                for (int probe = 0; probe < 2 * count; probe++) {
                    int offset = (probe + 1) / 2 * (probe % 2 == 0 ? 1 : -1);
                    int start = Math.floorMod(preferred + offset, count);
                    if (!free(places, start, needed)) {
                        continue;
                    }
                    for (int i = 0; i < needed; i++) {
                        places[(start + i) % count] = true;
                    }
                    return new double[] { centre + shake * jitter, (start + needed / 2.0) * width, centre };
                }
            }
        }

        private static boolean free(boolean[] places, int start, int needed) {
            for (int i = 0; i < needed; i++) {
                if (places[(start + i) % places.length]) {
                    return false;
                }
            }
            return true;
        }

        /** Le rayon du dernier anneau qui porte au moins un objet. */
        double outermost() {
            List<Double> used = used();
            return used.isEmpty() ? first : used.get(used.size() - 1);
        }

        /** Les rayons des anneaux qui portent au moins un objet. */
        List<Double> used() {
            List<Double> used = new ArrayList<>();
            for (int ring = 0; ring < taken.size(); ring++) {
                for (boolean place : taken.get(ring)) {
                    if (place) {
                        used.add(first + ring * step);
                        break;
                    }
                }
            }
            return used;
        }
    }

    /** Vitesse d'un anneau : les anneaux proches tournent plus vite, dans un sens propre à l'appareil. */
    private static double spin(double ringRadius, long seed) {
        return .01 * Math.sqrt(40_000 / Math.max(10_000, ringRadius)) * ((seed & 1) == 0 ? 1 : -1);
    }

    private static Satellite satellite(Random shared, String id, String parentId, String label, String detail,
                                       SatelliteKind kind, double orbit, double angle, double spin, double drift) {
        // Tiré de l'identifiant seul : un objet qui apparaît ne change pas la danse des autres
        Random random = new Random(id.hashCode() * 40_503L + 17);
        return new Satellite(id, parentId, label, detail, kind,
                Math.cos(angle) * orbit, Math.sin(angle) * orbit,
                drift * (.5 + .5 * random.nextDouble()),
                drift * (.5 + .5 * random.nextDouble()),
                0.045 + random.nextDouble() * 0.13,
                0.045 + random.nextDouble() * 0.13,
                random.nextDouble() * Math.PI * 2,
                random.nextDouble() * Math.PI * 2,
                orbit, angle, spin);
    }

    private static Star star(Device device, Star parent, double[] offset, Cortege cortege) {
        Map<String, Satellite> byId = new LinkedHashMap<>();
        for (Satellite node : cortege.satellites()) {
            byId.put(node.id(), node);
        }
        int hash = device.getId().hashCode();
        return new Star(device, parent,
                parent == null ? null : parent.device().getId(),
                offset[0], offset[1], offset[2],
                0.011 + (Math.abs(hash) % 7) * 0.0013,
                0.008 + (Math.abs(hash) % 5) * 0.0017,
                Math.abs(hash) % 628 / 100.0,
                Math.abs(hash >> 3) % 628 / 100.0,
                coreRadiusFor(device), cortege.primaryAdapterId(),
                cortege.satellites(), Collections.unmodifiableMap(byId),
                cortege.reach() + 1_200, cortege.rings());
    }

    private static double[] bandFor(DeviceType type) {
        return switch (type) {
            case GATEWAY_ROUTER -> GATEWAY_BAND;
            case LOCAL_HOST, LAN_PEER -> LAN_BAND;
            case REMOTE_SERVER -> REMOTE_BAND;
            case BLUETOOTH -> BLUETOOTH_BAND;
        };
    }

    /**
     * La taille d'un appareil dit ce que l'on sait de lui.
     *
     * Une machine dont on connaît douze ports, du matériel et un vrai nom n'a pas le même
     * poids qu'une adresse dont on n'a même pas résolu la carte réseau. Le concentrateur
     * domine sa constellation : c'est par lui que tout passe.
     */
    /** Le plus gros noyau que peut atteindre un appareil de ce type : base, savoir plafonné, variation maximale. */
    private static double maxCoreRadius(DeviceType type) {
        return baseRadius(type) * 2.6 * 1.24;
    }

    private static double baseRadius(DeviceType type) {
        return switch (type) {
            case GATEWAY_ROUTER -> 10_000;
            case LOCAL_HOST -> 9_000;
            case REMOTE_SERVER -> 2_900;
            case LAN_PEER -> 3_800;
            case BLUETOOTH -> 4_200;
        };
    }

    private static double coreRadiusFor(Device device) {
        // Des tailles vraies, en unités du monde : un appareil grossit à l'écran quand on s'approche
        double base = baseRadius(device.getType());
        double known = device.getEndpoints().size() * 220.0
                + device.getPeripherals().size() * 180.0
                + device.getAdapters().size() * 130.0;
        // Le libellé de repli commence par « Appareil » : c'est le seul signal disponible ici
        if (device.getName() != null && !device.getName().startsWith("Appareil")) {
            known += 670;
        }
        // Une variation contenue à 24 %, pour que deux voisins également connus ne soient pas
        // des jumeaux, sans jamais inverser la différence de savoir
        double variation = 1 + (Math.abs(device.getId().hashCode()) % 25) / 100.0;
        return (base + Math.min(known, base * 1.6)) * variation;
    }

    // --- Positions : fonctions du temps, partagées par le rendu et la sélection ---

    /**
     * Position d'un appareil : celle de son porteur, plus son propre décalage et sa
     * dérive. La récursion est ce qui fait qu'un client suit sa box en mouvement.
     */
    public static double starX(Star star, double seconds) {
        double base = star.parent() == null ? 0 : starX(star.parent(), seconds);
        return base + star.homeX() + star.drift() * Math.sin(star.freqX() * seconds + star.phaseX());
    }

    public static double starY(Star star, double seconds) {
        double base = star.parent() == null ? 0 : starY(star.parent(), seconds);
        return base + star.homeY()
                + star.drift() * SQUASH * Math.sin(star.freqY() * seconds + star.phaseY());
    }

    /** Position de repos absolue, dérive mise à zéro : sert au cadrage et aux étiquettes. */
    public static double restX(Star star) {
        return star.homeX() + (star.parent() == null ? 0 : restX(star.parent()));
    }

    public static double restY(Star star) {
        return star.homeY() + (star.parent() == null ? 0 : restY(star.parent()));
    }

    /** Dérive cumulée le long de la chaîne : de combien cet appareil peut s'écarter. */
    public static double driftReach(Star star) {
        return star.drift() + (star.parent() == null ? 0 : driftReach(star.parent()));
    }

    /** Rayon interdit autour du noyau : aucun objet ne descend en dessous. */
    public static double exclusionRadius(Star star) {
        return star.coreRadius() * EXCLUSION_FACTOR;
    }

    /** Rayon vrai d'un objet du cortège. */
    public static double satelliteRadius(SatelliteKind kind) {
        return switch (kind) {
            case ADAPTER -> ADAPTER_RADIUS;
            case PERIPHERAL -> PERIPHERAL_RADIUS;
            case PORT -> PORT_RADIUS;
            case SERVICE -> SERVICE_RADIUS;
        };
    }

    /**
     * Position d'un objet : il tourne sur son anneau autour de l'appareil, ou autour du
     * port dont il est le service, et dérive légèrement autour de sa place. Les anneaux
     * commencent au-delà de la barrière du noyau ; la barrière reste un garde-fou.
     */
    public static double[] position(Star star, Satellite node, double seconds) {
        double centreX;
        double centreY;
        Satellite parent = node.parentId() == null ? null : star.byId().get(node.parentId());
        if (parent == null) {
            centreX = starX(star, seconds);
            centreY = starY(star, seconds);
        } else {
            double[] anchor = position(star, parent, seconds);
            centreX = anchor[0];
            centreY = anchor[1];
        }

        double angle = node.angle() + node.spin() * seconds;
        double x = centreX + Math.cos(angle) * node.orbit() + node.driftX() * Math.sin(node.freqX() * seconds + node.phaseX());
        double y = centreY + Math.sin(angle) * node.orbit() + node.driftY() * Math.sin(node.freqY() * seconds + node.phaseY());

        double coreX = starX(star, seconds);
        double coreY = starY(star, seconds);
        double dx = x - coreX;
        double dy = y - coreY;
        double distance = Math.hypot(dx, dy);
        double floor = exclusionRadius(star);
        if (distance < floor && distance > 1e-9) {
            double push = floor / distance;
            x = coreX + dx * push;
            y = coreY + dy * push;
        }
        return new double[] { x, y };
    }

    public static double satelliteX(Star star, Satellite node, double seconds) {
        return position(star, node, seconds)[0];
    }

    public static double satelliteY(Star star, Satellite node, double seconds) {
        return position(star, node, seconds)[1];
    }

    /**
     * Le segment visible d'un lien entre deux corps : il vise exactement leurs deux
     * centres et s'arrête au bord de chacun. Renvoie null quand les deux corps se
     * recouvrent : il n'y a alors rien à tracer.
     */
    public static double[] clipBetween(double ax, double ay, double aRadius,
                                       double bx, double by, double bRadius) {
        double dx = bx - ax;
        double dy = by - ay;
        double length = Math.hypot(dx, dy);
        if (length <= aRadius + bRadius) {
            return null;
        }
        double ux = dx / length;
        double uy = dy / length;
        return new double[] {
                ax + ux * aRadius, ay + uy * aRadius,
                bx - ux * bRadius, by - uy * bRadius };
    }

    /**
     * Force du lien de constellation entre deux appareils : ils se relient quand ils
     * se rapprochent, et le trait s'éteint lorsqu'ils s'éloignent.
     */
    public static double deviceLinkStrength(double distance) {
        if (distance >= DEVICE_REACH) {
            return 0;
        }
        double t = 1 - distance / DEVICE_REACH;
        return t * t * (3 - 2 * t);
    }

    /** Portée de liaison propre à chaque nature. */
    public static double linkReach(SatelliteKind kind) {
        return switch (kind) {
            case ADAPTER -> ADAPTER_REACH;
            case PERIPHERAL -> PERIPHERAL_REACH;
            case PORT -> PORT_REACH;
            case SERVICE -> SERVICE_REACH;
        };
    }

    /**
     * Force du lien entre deux objets voisins : 1 au contact, 0 au seuil et au-delà.
     * La décroissance est lissée pour que le trait naisse et meure sans à-coup.
     */
    public static double linkStrength(SatelliteKind kind, double distance) {
        double reach = linkReach(kind);
        if (distance >= reach) {
            return 0;
        }
        double t = 1 - distance / reach;
        return t * t * (3 - 2 * t);
    }
}
