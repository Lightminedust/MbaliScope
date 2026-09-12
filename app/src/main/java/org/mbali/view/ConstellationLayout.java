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
 * Les gros appareils dansent sans jamais se rejoindre. Leur écartement n'est pas
 * réglé à l'œil : les racines sont posées sur un anneau dont le rayon est calculé
 * pour que la distance minimale reste supérieure à SYSTEM_GAP quelle que soit la
 * dérive, puisque chacune ne peut se rapprocher que de ROOT_DRIFT.
 *
 * Qui dépend de qui n'est pas décidé ici : c'est déduit des liens du modèle. La carte
 * ne peut donc pas affirmer une topologie que le modèle ne dit pas. Un appareil dont
 * on ignore le rattachement ne gravite autour de personne : il dérive dans le champ
 * extérieur, car le faire tourner autour d'un appareil serait affirmer sans preuve.
 *
 * Une barrière protège chaque noyau : quelle que soit la dérive, un objet ne peut pas
 * pénétrer à l'intérieur de son appareil.
 *
 * Le placement reste déterministe : un appareil retrouve sa place d'un scan au
 * suivant, car tout est tiré de son identifiant. Seule exception assumée : l'angle
 * des systèmes dépend de leur nombre, donc l'apparition d'un nouveau système
 * réarrange les systèmes entre eux — jamais un appareil vis-à-vis du sien.
 */
public final class ConstellationLayout {

    public enum SatelliteKind { ADAPTER, PERIPHERAL, PORT, SERVICE }

    /**
     * Un objet du nuage. Sa position est sa position de repos plus une dérive à deux
     * fréquences distinctes, ce qui décrit une courbe de Lissajous jamais bouclée.
     */
    public record Satellite(String id, String parentId, String label, String detail,
                            SatelliteKind kind, double homeX, double homeY,
                            double driftX, double driftY,
                            double freqX, double freqY, double phaseX, double phaseY) {
    }

    /**
     * Un appareil et son nuage.
     *
     * parent est l'appareil autour duquel celui-ci gravite, ou null s'il est la racine
     * de son système. homeX et homeY sont un DÉCALAGE par rapport à ce parent, et non
     * une position absolue : c'est ce qui permet à un client de suivre sa box pendant
     * qu'elle dérive, au lieu de rester planté où elle se trouvait au moment du scan.
     */
    public record Star(Device device, Star parent, String parentId, double homeX, double homeY,
                       double drift, double freqX, double freqY, double phaseX, double phaseY,
                       double coreRadius, String primaryAdapterId,
                       List<Satellite> satellites, Map<String, Satellite> byId,
                       double systemRadius) {

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

    // Bandes de dispersion d'un nuage : un objet est tiré au hasard dans son anneau,
    // pas réparti à intervalle régulier.
    // Le cortège d'une machine tenait dans 12 000 unités alors que la carte en fait
    // plus de 300 000 : tout se tassait en une pastille illisible. Les orbites sont
    // désormais à l'échelle de l'espace qui les entoure, et surtout à l'échelle des
    // corps eux-mêmes, qui ont maintenant une taille vraie et non une taille d'écran.
    private static final double[] ADAPTER_BAND = { 12_000, 22_000 };
    private static final double[] PERIPHERAL_BAND = { 28_000, 42_000 };
    private static final double[] PORT_BAND = { 48_000, 68_000 };
    private static final double[] SERVICE_BAND = { 2_500, 5_000 };

    // Bandes des appareils qui gravitent autour d'un autre : vastes, et irrégulières.
    private static final double[] GATEWAY_BAND = { 26_000, 40_000 };
    private static final double[] LAN_BAND = { 52_000, 98_000 };
    private static final double[] REMOTE_BAND = { 112_000, 152_000 };

    /**
     * Écart minimal garanti entre deux systèmes, quelle que soit la dérive. Il doit
     * dépasser la somme de leurs rayons : le système de la box atteint environ
     * 105 000 (ses clients tournent jusqu'à 98 000), celui de cette machine environ
     * 12 000. 260 000 laisse donc un vide franc entre les deux.
     */
    public static final double SYSTEM_GAP = 260_000;

    /** Amplitude de la danse d'un gros appareil. */
    public static final double ROOT_DRIFT = 14_000;

    /**
     * Dispersion des systèmes, en fraction du secteur qui leur revient, et en fraction
     * du rayon de l'anneau. Sans elle, deux systèmes tombaient à 0 et π : un axe
     * horizontal parfait, que l'œil lit immédiatement comme une figure imposée.
     * L'écartement garanti tient compte de cette dispersion au lieu de la subir.
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
        // seule preuve que la box concentre quoi que ce soit. Elle était alors reléguée
        // dans le champ extérieur et ce PC reprenait le centre — exactement le défaut
        // que ce découpage en systèmes doit supprimer.
        Set<String> carriesOthers = new LinkedHashSet<>(uplinks.values());

        // La machine depuis laquelle on observe n'est le satellite de personne : elle
        // forme un système à part entière, à côté de la box. Le modèle conserve le
        // lien réel entre les deux ; c'est le rendu qui le trace d'un système à
        // l'autre, puisque l'orbite ne peut pas l'exprimer ici. Détacher est une
        // décision de mise en page, elle n'efface aucun fait.
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

        Map<String, double[]> offsets = new LinkedHashMap<>();
        double ring = rootRing(roots.size());
        for (int index = 0; index < roots.size(); index++) {
            offsets.put(roots.get(index).getId(),
                    rootSlot(roots.get(index), index, roots.size(), ring));
        }
        for (Device lost : adrift) {
            offsets.put(lost.getId(), outerField(lost, ring));
        }
        for (Device device : devices) {
            offsets.computeIfAbsent(device.getId(), key -> memberOffset(device));
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
                built.put(device.getId(), star(device, built.get(parentId),
                        offset[0], offset[1], offset[2]));
                waiting.remove();
                placedSomething = true;
            }
        }
        // Filet de sécurité : plutôt que de disparaître, un appareil dont le parent
        // reste introuvable est posé sans parent.
        for (Device stranded : pending) {
            double[] offset = offsets.get(stranded.getId());
            built.put(stranded.getId(), star(stranded, null, offset[0], offset[1], offset[2]));
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
     *
     * C'est la correction d'un défaut de fond : la carte plaçait cette machine à
     * l'origine et tous les autres appareils autour d'elle, ce qui affirmait que tout
     * passait par elle.
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
     * La corde entre deux emplacements voisins vaut 2·R·sin(π/n). On veut qu'elle
     * dépasse SYSTEM_GAP + 2·ROOT_DRIFT, puisque deux systèmes voisins ne peuvent se
     * rapprocher que de leur dérive. Le rayon s'en déduit, et s'agrandit donc tout
     * seul quand des systèmes s'ajoutent.
     */
    private static double rootRing(int systems) {
        if (systems <= 1) {
            return 0;
        }
        // Deux systèmes voisins peuvent se rapprocher de trois façons : leur dérive,
        // leur dispersion angulaire, et leur dispersion radiale. L'écart angulaire au
        // pire vaut (2π/n)(1 − ROOT_JITTER), et le rayon au pire (1 − SPREAD)·R ;
        // pour deux points de rayons différents, la distance est minorée par celle
        // qu'ils auraient au plus petit des deux rayons.
        double worstAngle = (Math.PI / systems) * (1 - ROOT_JITTER);
        double worstRadius = 1 - ROOT_RADIAL_SPREAD;
        return (SYSTEM_GAP + 2 * ROOT_DRIFT) / (2 * worstRadius * Math.sin(worstAngle));
    }

    /**
     * Emplacement d'un système sur l'anneau, dispersé dans le secteur qui lui revient.
     * Un système seul se pose à l'origine, n'ayant personne dont s'écarter.
     */
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
        // L'anneau n'est pas aplati : la garantie d'écartement se lit alors directement
        // sur la corde, sans correction à faire.
        return new double[] { Math.cos(angle) * radius, Math.sin(angle) * radius, ROOT_DRIFT };
    }

    /**
     * Place un appareil dans le nuage de son porteur, en décalage relatif. Le tirage ne
     * dépend que de son identifiant : découvrir un voisin de plus ne déplace donc aucun
     * appareil déjà posé.
     */
    private static double[] memberOffset(Device device) {
        Random random = new Random(device.getId().hashCode() * 2_654_435_761L);
        double[] band = bandFor(device.getType());
        double radius = band[0] + random.nextDouble() * (band[1] - band[0]);
        double angle = random.nextDouble() * Math.PI * 2;
        return new double[] {
                Math.cos(angle) * radius,
                Math.sin(angle) * radius * 0.74,
                2_400 + random.nextDouble() * 2_600 };
    }

    /**
     * Un appareil dont on ignore le rattachement dérive au-delà des systèmes. Le faire
     * tourner autour de l'un d'eux affirmerait une appartenance qu'on n'a pas établie,
     * maintenant que l'orbite tient lieu de lien.
     */
    private static double[] outerField(Device device, double ring) {
        Random random = new Random(device.getId().hashCode() * 7_919L + 31);
        double radius = ring + OUTER_FIELD[0]
                + random.nextDouble() * (OUTER_FIELD[1] - OUTER_FIELD[0]);
        double angle = random.nextDouble() * Math.PI * 2;
        return new double[] {
                Math.cos(angle) * radius,
                Math.sin(angle) * radius * 0.74,
                2_400 + random.nextDouble() * 2_600 };
    }

    private static Star star(Device device, Star parent,
                             double homeX, double homeY, double drift) {
        Random random = new Random(device.getId().hashCode() * 40_503L + 17);
        List<Satellite> nodes = new ArrayList<>();

        String primaryAdapterId = null;
        for (NetworkAdapter adapter : device.getAdapters()) {
            String id = "adapter:" + adapter.name() + ':' + adapter.ipAddress();
            nodes.add(node(random, id, null, adapter.kind().getLabel(),
                    adapter.displayName() + " · " + adapter.ipAddress(),
                    SatelliteKind.ADAPTER, ADAPTER_BAND));
            // La carte qui porte l'adresse de l'appareil est celle par laquelle il
            // atteint le réseau : c'est d'elle que part le trait vers l'autre système.
            if (primaryAdapterId == null && adapter.ipAddress().equals(device.getIpAddress())) {
                primaryAdapterId = id;
            }
        }
        if (primaryAdapterId == null && !nodes.isEmpty()) {
            primaryAdapterId = nodes.get(0).id();
        }

        for (Peripheral peripheral : device.getPeripherals()) {
            nodes.add(node(random, "peripheral:" + peripheral.hardwareId(), null,
                    peripheral.name(), peripheral.kind().getLabel(),
                    SatelliteKind.PERIPHERAL, PERIPHERAL_BAND));
        }

        for (PortEndpoint port : device.getEndpoints()) {
            String portId = "port:" + port.getPortNumber() + ':' + port.getLocalAddress()
                    + ':' + port.getOwner();
            String detail = port.getOwner()
                    + (port.getLocalAddress().isBlank() ? "" : " · " + port.getLocalAddress());
            nodes.add(node(random, portId, null, "Port " + port.getPortNumber(), detail,
                    SatelliteKind.PORT, PORT_BAND));

            String evidence = port.getServiceEvidence().isBlank()
                    ? (port.isServiceVerified() ? "Service confirmé" : "Attribution indicative")
                    : port.getServiceEvidence();
            nodes.add(node(random, portId + ":service", portId, port.getName(), evidence,
                    SatelliteKind.SERVICE, SERVICE_BAND));
        }

        Map<String, Satellite> byId = new LinkedHashMap<>();
        double reach = 0;
        for (Satellite node : nodes) {
            byId.put(node.id(), node);
            reach = Math.max(reach, Math.hypot(node.homeX(), node.homeY())
                    + Math.max(node.driftX(), node.driftY()));
        }
        double core = coreRadiusFor(device);
        double systemRadius = nodes.isEmpty() ? core * 3 : reach + 1_200;

        return new Star(device, parent,
                parent == null ? null : parent.device().getId(),
                homeX, homeY, drift,
                0.011 + (Math.abs(device.getId().hashCode()) % 7) * 0.0013,
                0.008 + (Math.abs(device.getId().hashCode()) % 5) * 0.0017,
                Math.abs(device.getId().hashCode()) % 628 / 100.0,
                Math.abs(device.getId().hashCode() >> 3) % 628 / 100.0,
                core, primaryAdapterId,
                List.copyOf(nodes), Collections.unmodifiableMap(byId), systemRadius);
    }

    /**
     * Tire une position de repos au hasard dans l'anneau de la famille, puis deux
     * fréquences de dérive volontairement différentes : c'est ce décalage qui
     * empêche les objets de rester alignés.
     */
    private static Satellite node(Random random, String id, String parentId, String label,
                                  String detail, SatelliteKind kind, double[] band) {
        double radius = band[0] + random.nextDouble() * (band[1] - band[0]);
        double angle = random.nextDouble() * Math.PI * 2;
        double scale = band == SERVICE_BAND ? 0.22 : 1;
        return new Satellite(id, parentId, label, detail, kind,
                Math.cos(angle) * radius,
                Math.sin(angle) * radius * 0.74,
                (420 + random.nextDouble() * 1_100) * scale,
                (420 + random.nextDouble() * 1_100) * scale,
                0.045 + random.nextDouble() * 0.13,
                0.045 + random.nextDouble() * 0.13,
                random.nextDouble() * Math.PI * 2,
                random.nextDouble() * Math.PI * 2);
    }

    private static double[] bandFor(DeviceType type) {
        return switch (type) {
            case GATEWAY_ROUTER -> GATEWAY_BAND;
            case LOCAL_HOST, LAN_PEER -> LAN_BAND;
            case REMOTE_SERVER -> REMOTE_BAND;
        };
    }

    /**
     * La taille d'un appareil dit ce que l'on sait de lui.
     *
     * Tous les appareils avaient le même rayon : la carte affirmait donc qu'ils
     * pèsent tous pareil, ce qui est faux. Une machine dont on connaît douze ports,
     * du matériel et un vrai nom n'a pas le même poids qu'une adresse dont on n'a
     * même pas résolu la carte réseau.
     *
     * Le concentrateur domine sa constellation : c'est par lui que tout passe.
     */
    private static double coreRadiusFor(Device device) {
        // Ces rayons sont des tailles vraies, en unités du monde : un appareil garde
        // la même taille quel que soit le zoom, et grossit donc à l'écran quand on
        // s'approche, comme un astre sur une carte du ciel.
        double base = switch (device.getType()) {
            case GATEWAY_ROUTER -> 5_200;
            case LOCAL_HOST -> 4_200;
            case REMOTE_SERVER -> 1_800;
            case LAN_PEER -> 1_500;
        };
        double known = device.getEndpoints().size() * 140.0
                + device.getPeripherals().size() * 110.0
                + device.getAdapters().size() * 80.0;
        // Un appareil qui a livré un nom en dit plus qu'une adresse anonyme. Le libellé
        // de repli commence par « Appareil » ; c'est le seul signal disponible ici.
        if (device.getName() != null && !device.getName().startsWith("Appareil")) {
            known += 420;
        }
        // Une variation propre à chaque appareil, pour que deux voisins également
        // connus ne soient pas pour autant des jumeaux.
        double variation = 1 + (Math.abs(device.getId().hashCode()) % 41) / 100.0;
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
                + star.drift() * 0.74 * Math.sin(star.freqY() * seconds + star.phaseY());
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

    /**
     * Position d'un objet, barrière comprise. Le centre de dérive est l'appareil, ou
     * le port dont l'objet est le satellite ; puis, si la dérive l'a fait entrer dans
     * le noyau de l'appareil, il est repoussé sur la limite au lieu de la traverser.
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

        double x = centreX + node.homeX() + node.driftX() * Math.sin(node.freqX() * seconds + node.phaseX());
        double y = centreY + node.homeY() + node.driftY() * Math.sin(node.freqY() * seconds + node.phaseY());

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
     * Force du lien de constellation entre deux appareils : ils se relient quand ils
     * se rapprochent, comme les objets d'une même famille, et le trait s'éteint
     * lorsqu'ils s'éloignent.
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
