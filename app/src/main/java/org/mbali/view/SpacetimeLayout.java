package org.mbali.view;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

import org.mbali.model.ProcessSnapshot;
import org.mbali.model.ProcessTree;

/**
 * La géométrie de l'espace-temps des processus. Aucune dépendance JavaFX : elle se teste.
 *
 * Deux grandeurs, deux effets distincts :
 *   — la MÉMOIRE est de l'espace : elle donne sa taille à chaque corps, à l'échelle. Le
 *     rayon suit la racine carrée de la part de mémoire, pour que ce soit la SURFACE qui
 *     soit proportionnelle : deux fois plus de mémoire, deux fois plus de place ;
 *   — le CPU est la masse : il creuse la nappe sous le corps. Plus un processus consomme,
 *     plus l'entonnoir est profond, et plus ses anomalies tournent vite.
 *
 * Une masse est une application — tous les processus d'un même exécutable. Son principal
 * est celui qu'une autre application a lancé ; les autres sont ses anomalies, les
 * sous-processus, placés en orbite sur des anneaux calculés pour qu'aucun ne se heurte.
 *
 * Le CPU est mesuré entre deux relevés. Les positions et les places orbitales sont
 * conservées : une mesure ne déclenche jamais une nouvelle simulation de placement.
 */
public final class SpacetimeLayout {

    /**
     * Une anomalie : un sous-processus, sa taille à l'échelle de sa propre mémoire, et sa
     * place sur son anneau — l'angle de départ, auquel s'ajoute la rotation de l'anneau.
     */
    public record Anomaly(ProcessSnapshot process, double radius, double cpuShare,
                          int ring, double orbitRadius, double slotAngle) {
    }

    /**
     * Une masse : une application. Le corps porte toute la mémoire de l'application ; le
     * système s'étend jusqu'au bord de son anneau le plus lointain.
     */
    public record Mass(String key, String name, ProcessSnapshot principal,
                       List<Anomaly> anomalies, int processCount,
                       long memoryBytes, boolean memoryKnown, double memoryShare,
                       double cpuShare, double homeX, double homeY,
                       double bodyRadius, double systemRadius, double wellDepth) {
    }

    /** Un relevé mis en forme. */
    public record Field(List<Mass> masses, int processCount, long memoryBytes,
                        OptionalLong totalMemoryBytes, int cores, Instant capturedAt,
                        double extent, boolean cpuSampled) {
    }

    /**
     * Un puits de la nappe, tel que le rendu le creuse à un instant donné.
     *
     * @param depth profondeur au centre, en unités du monde
     * @param core  largeur de la gorge : plus elle est étroite, plus l'entonnoir est raide
     * @param reach au-delà, la nappe est intacte
     * @param pull  part de l'espace attirée vers le centre, bornée ici même pour que la
     *              garantie de non-repliement ne dépende d'aucun appelant
     */
    public record Well(double x, double y, double depth, double core, double reach, double pull) {
        public Well {
            core = Math.max(1, core);
            reach = Math.max(core, reach);
            depth = Math.max(0, depth);
            pull = Math.max(0, Math.min(MAX_PULL, pull));
        }
    }

    /** Pas de la grille de référence, en unités du monde. */
    public static final double GRID_STEP = 1_000;

    /** Profondeur d'un entonnoir quand toute la machine est occupée. */
    public static final double MAX_WELL_DEPTH = 16_000;

    /**
     * Attraction maximale de la nappe vers un centre. Tant qu'elle reste inférieure à 1,
     * l'attraction resserre la grille sans qu'aucune ligne ne se replie ; à 0,22, deux
     * points ne se rapprochent jamais de plus de 22 % — c'est la marge que prennent les
     * orbites pour que les anomalies ne se heurtent pas une fois l'espace courbé.
     */
    public static final double MAX_PULL = 0.22;

    /** Taille d'un corps qui occuperait toute la mémoire. */
    static final double BODY_SCALE = 16_000;
    /** En deçà, un corps ne serait plus qu'un point : la plus petite taille gardée. */
    static final double BODY_MIN = 60;

    /** Distance libre entre deux surfaces voisines d'un système. */
    private static final double ORBIT_CLEARANCE = 650;
    /** Espace réservé sur un anneau, en diamètres d'anomalie. */
    private static final double SLOT_PADDING = 2.4;
    private static final double SLOT_GAP = 40;
    /** Les écarts sont élargis d'autant que l'attraction peut les resserrer. */
    private static final double PULL_ALLOWANCE = 1 / ((1 - MAX_PULL) * Math.sin(Perspective.MIN_ELEVATION));

    /** Espace libre entre deux territoires : les familles et leurs puits respirent. */
    private static final double SPACING = 15_000;

    /** Une anomalie à cette distance d'un corps inactif fait un tour en ORBIT_PERIOD secondes. */
    private static final double ORBIT_REFERENCE = 8_000;
    private static final double ORBIT_PERIOD = 200;

    /** Le processus inactif de Windows : son temps CPU est du temps libre, pas une consommation. */
    private static final long IDLE_PID = 0;

    private static final Comparator<ProcessSnapshot> OLDEST_FIRST =
            Comparator.comparing((ProcessSnapshot p) -> p.startTime().orElse(Instant.MAX))
                    .thenComparingLong(ProcessSnapshot::pid);

    private SpacetimeLayout() {
    }

    public static Field compute(List<ProcessSnapshot> processes, OptionalLong totalMemory,
                                int cores, Instant now, OptionalLong ownPid) {
        return compute(processes, totalMemory, cores, now, ownPid, null);
    }

    /**
     * @param previous le relevé précédent, ou null : chaque application repart de sa place
     *                 d'avant, pour que la carte ne se réorganise pas à chaque relevé
     */
    public static Field compute(List<ProcessSnapshot> processes, OptionalLong totalMemory,
                                int cores, Instant now, OptionalLong ownPid, Field previous) {
        List<ProcessSnapshot> observed = withoutOwnTools(processes, ownPid);
        ProcessTree tree = new ProcessTree(observed);
        int usableCores = Math.max(1, cores);
        Map<Long, ProcessSnapshot> before = new HashMap<>();
        Map<String, Mass> oldMasses = new HashMap<>();
        if (previous != null) {
            for (Mass mass : previous.masses()) {
                oldMasses.put(mass.key(), mass);
                before.put(mass.principal().pid(), mass.principal());
                mass.anomalies().forEach(a -> before.put(a.process().pid(), a.process()));
            }
        }
        double interval = previous == null ? 0 : seconds(Duration.between(previous.capturedAt(), now));
        Map<Long, Double> cpuShares = new HashMap<>();
        for (ProcessSnapshot process : observed) {
            cpuShares.put(process.pid(), cpuShare(process, before.get(process.pid()), usableCores, interval));
        }

        // Clés triées : même relevé, même ordre, même carte.
        Map<String, List<ProcessSnapshot>> byApplication = new TreeMap<>();
        long observedMemory = 0;
        for (ProcessSnapshot process : observed) {
            byApplication.computeIfAbsent(keyOf(process), key -> new ArrayList<>()).add(process);
            observedMemory += process.memoryBytes().orElse(0);
        }
        // Sans total connu, on rapporte chaque application à la mémoire observée.
        double denominator = totalMemory.isPresent() && totalMemory.getAsLong() > 0
                ? totalMemory.getAsLong() : Math.max(1, observedMemory);

        List<Mass> masses = new ArrayList<>();
        for (Map.Entry<String, List<ProcessSnapshot>> application : byApplication.entrySet()) {
            List<ProcessSnapshot> members = application.getValue();
            ProcessSnapshot principal = principalOf(members, tree);

            long memory = 0;
            boolean memoryKnown = false;
            double cpu = 0;
            for (ProcessSnapshot member : members) {
                if (member.memoryBytes().isPresent()) {
                    memory += member.memoryBytes().getAsLong();
                    memoryKnown = true;
                }
                cpu += cpuShares.get(member.pid());
            }
            double memoryShare = clamp01(memory / denominator);
            double body = bodyRadius(memoryShare);

            // Les plus anciens sur les anneaux intérieurs : un nouveau sous-processus prend
            // place à l'extérieur sans bousculer ceux qui tournent déjà
            List<ProcessSnapshot> children = members.stream()
                    .filter(member -> member != principal)
                    .sorted(OLDEST_FIRST)
                    .toList();
            Mass old = oldMasses.get(application.getKey());
            List<Anomaly> anomalies = arrange(body, children, denominator, cpuShares, old);
            double system = body;
            for (Anomaly anomaly : anomalies) {
                system = Math.max(system, anomaly.orbitRadius() + anomaly.radius());
            }

            masses.add(new Mass(application.getKey(), principal.name(), principal, anomalies,
                    members.size(), memory, memoryKnown, memoryShare, clamp01(cpu), 0, 0,
                    body, system, wellDepth(cpu, usableCores)));
        }

        List<Mass> placed = place(masses, previous);
        double extent = 6_000;
        for (Mass mass : placed) {
            extent = Math.max(extent, Math.hypot(mass.homeX(), mass.homeY()) + mass.systemRadius());
        }
        return new Field(List.copyOf(placed), observed.size(), observedMemory, totalMemory,
                usableCores, now, extent, interval > 0);
    }

    /** Rayon d'un corps : la surface, et non le rayon, suit la part de mémoire. */
    static double bodyRadius(double memoryShare) {
        return Math.max(BODY_MIN, BODY_SCALE * Math.sqrt(clamp01(memoryShare)));
    }

    /**
     * Profondeur de l'entonnoir, selon les cœurs réellement occupés.
     *
     * Rapportée à toute la machine, la consommation restait minuscule : sur les 12 cœurs de
     * la machine réelle, une application qui en occupe un entier ne pèse que 8 %. On compte
     * donc en cœurs : un dixième de cœur se voit, un cœur entier creuse franchement, et
     * plusieurs cœurs se distinguent encore sans jamais dépasser la profondeur maximale.
     */
    static double wellDepth(double cpuShare, int cores) {
        double coresUsed = clamp01(cpuShare) * Math.max(1, cores);
        return MAX_WELL_DEPTH * (1 - Math.exp(-Math.sqrt(coresUsed) * 1.2));
    }

    /**
     * Le puits d'une masse. La gorge suit la taille du corps ; la portée englobe tout le
     * système, pour que les anomalies tournent sur la pente de l'entonnoir ; l'attraction
     * suit la profondeur.
     */
    public static Well wellOf(double x, double y, double bodyRadius, double systemRadius, double depth) {
        // La portée couvre tout le territoire de la famille, quelle que soit sa mémoire : un
        // processus système de 9 Mo qui consomme beaucoup doit courber l'espace aussi loin
        // qu'une grosse application. Avec une portée à la taille du corps, son puits ne
        // faisait qu'un point en vue d'ensemble. La portée ne dépend toujours pas du CPU :
        // c'est la profondeur qui dit l'intensité.
        double reach = Math.max(TERRITORY_MIN, Math.max(systemRadius * 1.38, bodyRadius * 2.8 + 1_400));
        double core = Math.max(bodyRadius * 0.7 + 250, reach * 0.16);
        // Racine carrée : une charge modérée resserre déjà visiblement la grille vers le centre
        return new Well(x, y, depth, core, reach, MAX_PULL * Math.sqrt(Math.min(1, depth / MAX_WELL_DEPTH)));
    }

    /**
     * Vitesse angulaire d'une orbite, en radians par seconde.
     *
     * La loi de Kepler : plus une orbite est lointaine, plus elle est lente, en r^(−3/2).
     * La masse — le CPU — accélère toutes les orbites de son système. Sur un même anneau,
     * toutes les anomalies ont donc la même vitesse et gardent leur écart pour toujours.
     */
    public static double angularSpeed(double orbitRadius, double wellDepth) {
        double mass = 1 + 0.35 * Math.min(1, Math.max(0, wellDepth) / MAX_WELL_DEPTH);
        double ratio = ORBIT_REFERENCE / Math.max(1, orbitRadius);
        return Math.min(2 * Math.PI / 140,
                2 * Math.PI / ORBIT_PERIOD * Math.sqrt(mass) * ratio * Math.sqrt(ratio));
    }

    /** Un PID réutilisé ou une mesure manquante n'est pas une consommation mesurée. */
    static double cpuShare(ProcessSnapshot current, ProcessSnapshot previous, int cores, double interval) {
        if (current.pid() == IDLE_PID || interval <= 0 || !current.isSameProcessAs(previous)
                || current.cpuTime().isEmpty() || previous.cpuTime().isEmpty()) {
            return 0;
        }
        double delta = seconds(current.cpuTime().get().minus(previous.cpuTime().get()));
        return clamp01(delta / interval / Math.max(1, cores));
    }

    private static double seconds(Duration duration) {
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    /**
     * Part moyenne d'un processus dans la puissance de la machine, depuis son lancement :
     * temps CPU consommé, divisé par sa durée de vie et par le nombre de cœurs.
     */
    public static double averageCpuShare(ProcessSnapshot process, int cores, Instant now) {
        if (process.pid() == IDLE_PID || process.cpuTime().isEmpty() || process.startTime().isEmpty()) {
            return 0;
        }
        double lifetime = Duration.between(process.startTime().get(), now).toNanos();
        if (lifetime <= 0) {
            return 0;
        }
        return clamp01(process.cpuTime().get().toNanos() / lifetime / Math.max(1, cores));
    }

    /**
     * Place les anomalies sur des anneaux, de l'intérieur vers l'extérieur.
     *
     * Deux garanties, qui font qu'aucune anomalie ne peut en heurter une autre :
     *   — entre deux anneaux, l'écart dépasse la somme des plus grands rayons de chacun ;
     *   — sur un même anneau, chaque anomalie reçoit une part d'angle proportionnelle à sa
     *     taille. Toutes tournent à la même vitesse : l'écart entre voisines ne change jamais.
     * Chaque écart est élargi de la marge d'attraction : même l'espace courbé ne les rapproche
     * pas au point de se toucher.
     */
    static List<Anomaly> arrange(double bodyRadius, List<ProcessSnapshot> children,
                                 double memoryDenominator, int cores, Instant now) {
        return arrange(bodyRadius, children, memoryDenominator, Map.of(), null);
    }

    private static List<Anomaly> arrange(double bodyRadius, List<ProcessSnapshot> children,
                                       double memoryDenominator, Map<Long, Double> cpu, Mass previous) {
        // Garder les angles et les rayons orbitaux tant que la place réservée suffit.
        // Les nouveaux processus réutilisent les places libérées, puis un anneau
        // extérieur si nécessaire. Le renouvellement des processus ne gonfle pas la carte.
        Map<Long, Anomaly> old = new HashMap<>();
        if (previous != null) {
            previous.anomalies().forEach(a -> old.put(a.process().pid(), a));
        }
        List<Anomaly> retained = new ArrayList<>();
        List<ProcessSnapshot> newcomers = new ArrayList<>();
        for (ProcessSnapshot child : children) {
            Anomaly known = old.get(child.pid());
            double radius = bodyRadius(child.memoryBytes().orElse(0) / memoryDenominator);
            // Un processus qui grossit garde sa place tant qu'il ne touche réellement personne :
            // on le vérifie pour lui seul. Mesuré sur la machine réelle : avec une tolérance fixe
            // de 12 %, les processus de code.exe perdaient leur place à chaque relevé, partaient
            // sur un anneau extérieur, et la famille grossissait de 8 000 unités toutes les
            // trois secondes en chassant ses voisines.
            boolean kept = false;
            if (known != null && child.isSameProcessAs(known.process())
                    && bodyRadius * 1.3 + radius + 100 < known.orbitRadius()
                            * (1 - MAX_PULL) * Math.sin(Perspective.MIN_ELEVATION)) {
                retained.add(new Anomaly(child, radius, cpu.getOrDefault(child.pid(), 0.0),
                        known.ring(), known.orbitRadius(), known.slotAngle()));
                kept = separated(retained);
                if (!kept) retained.remove(retained.size() - 1);
            }
            if (!kept) newcomers.add(child);
        }
        List<Anomaly> vacant = old.values().stream()
                .filter(slot -> retained.stream().noneMatch(a -> a.ring() == slot.ring() && a.slotAngle() == slot.slotAngle()))
                .sorted(Comparator.comparingDouble(Anomaly::orbitRadius).thenComparingDouble(Anomaly::slotAngle)).toList();
        List<ProcessSnapshot> unplaced = new ArrayList<>();
        for (ProcessSnapshot newcomer : newcomers) {
            double radius = bodyRadius(newcomer.memoryBytes().orElse(0) / memoryDenominator);
            boolean fitted = false;
            for (Anomaly slot : vacant) {
                if (bodyRadius * 1.3 + radius + 100 >= slot.orbitRadius()
                        * (1 - MAX_PULL) * Math.sin(Perspective.MIN_ELEVATION)) continue;
                Anomaly candidate = new Anomaly(newcomer, radius, cpu.getOrDefault(newcomer.pid(), 0.0),
                        slot.ring(), slot.orbitRadius(), slot.slotAngle());
                retained.add(candidate);
                if (separated(retained)) {
                    fitted = true;
                    break;
                }
                retained.remove(retained.size() - 1);
            }
            if (!fitted) unplaced.add(newcomer);
        }
        children = unplaced;
        int count = children.size();
        double[] radii = new double[count];
        for (int i = 0; i < count; i++) {
            OptionalLong memory = children.get(i).memoryBytes();
            radii[i] = memory.isPresent() ? bodyRadius(memory.getAsLong() / memoryDenominator) : BODY_MIN;
        }

        List<Anomaly> anomalies = new ArrayList<>(retained);
        double previousOrbit = 0;
        double previousWidest = bodyRadius * 1.4;
        int ring = 0;
        for (Anomaly anomaly : retained) {
            if (anomaly.orbitRadius() > previousOrbit) {
                previousOrbit = anomaly.orbitRadius();
                previousWidest = anomaly.radius();
            } else if (anomaly.orbitRadius() == previousOrbit) {
                previousWidest = Math.max(previousWidest, anomaly.radius());
            }
            ring = Math.max(ring, anomaly.ring() + 1);
        }
        int next = 0;
        while (next < count) {
            int first = next;
            double widest = 0;
            double orbit = 0;
            double occupied = 0;
            while (next < count) {
                double candidateWidest = Math.max(widest, radii[next]);
                double candidateOrbit = previousOrbit
                        + (previousWidest + ORBIT_CLEARANCE + candidateWidest) * PULL_ALLOWANCE;
                double candidateOccupied = occupied + slotWidth(radii[next]);
                // Une part d'angle de π·largeur/(2·orbite) par anomalie suffit à séparer les
                // corps d'une corde de la somme de leurs rayons : l'anneau les tient toutes
                // tant que la somme des largeurs ne dépasse pas deux fois son rayon.
                if (next > first && candidateOccupied > 2 * candidateOrbit) {
                    break;
                }
                widest = candidateWidest;
                orbit = candidateOrbit;
                occupied = candidateOccupied;
                next++;
            }

            // La place restante est répartie : les écarts ne font que grandir
            double stretch = 2 * Math.PI / (Math.PI * occupied / orbit);
            double angle = ring * 2.39996;
            for (int i = first; i < next; i++) {
                double half = Math.PI * slotWidth(radii[i]) / (2 * orbit) * stretch;
                angle += half;
                anomalies.add(new Anomaly(children.get(i), radii[i],
                        cpu.getOrDefault(children.get(i).pid(), 0.0), ring, orbit, angle));
                angle += half;
            }
            previousOrbit = orbit;
            previousWidest = widest;
            ring++;
        }
        return List.copyOf(anomalies);
    }

    private static boolean separated(List<Anomaly> anomalies) {
        for (int i = 0; i < anomalies.size(); i++) {
            Anomaly a = anomalies.get(i);
            for (int j = i + 1; j < anomalies.size(); j++) {
                Anomaly b = anomalies.get(j);
                double distance = a.ring() == b.ring()
                        ? 2 * a.orbitRadius() * Math.abs(Math.sin((a.slotAngle() - b.slotAngle()) / 2))
                        : Math.abs(a.orbitRadius() - b.orbitRadius());
                // La même exigence que celle qui a construit les anneaux, pas davantage.
                // Mesuré sur la machine réelle : avec 1,25 × les rayons + 80, un anneau tout juste
                // créé était jugé trop serré au relevé suivant ; ses satellites repartaient sur un
                // anneau plus lointain, qui échouait à son tour, et code.exe grossissait sans fin.
                if (distance * (1 - MAX_PULL) * Math.sin(Perspective.MIN_ELEVATION)
                        < a.radius() + b.radius() + 50) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double slotWidth(double radius) {
        return radius * SLOT_PADDING * PULL_ALLOWANCE + SLOT_GAP;
    }

    /**
     * Le principal est celui dont le parent n'appartient pas à la même application : c'est
     * lui qui a été lancé, les autres sont ce qu'il a lancé à son tour. S'il y en a
     * plusieurs, le plus ancien l'emporte.
     */
    static ProcessSnapshot principalOf(List<ProcessSnapshot> members, ProcessTree tree) {
        List<ProcessSnapshot> heads = members.stream()
                .filter(member -> tree.parentOf(member)
                        .map(parent -> !keyOf(parent).equals(keyOf(member)))
                        .orElse(true))
                .toList();
        return (heads.isEmpty() ? members : heads).stream().min(OLDEST_FIRST).orElseThrow();
    }

    /**
     * Écarte les processus que MbaliScope lance lui-même pour mesurer — PowerShell et ses
     * enfants. Sans cela, l'instrument de mesure apparaîtrait comme une masse consommant du
     * CPU à chaque relevé. MbaliScope lui-même reste visible : il consomme vraiment.
     */
    static List<ProcessSnapshot> withoutOwnTools(List<ProcessSnapshot> processes, OptionalLong ownPid) {
        if (ownPid.isEmpty()) {
            return processes;
        }
        ProcessSnapshot self = processes.stream()
                .filter(process -> process.pid() == ownPid.getAsLong())
                .findFirst().orElse(null);
        if (self == null) {
            return processes;
        }
        ProcessTree tree = new ProcessTree(processes);
        Set<Long> tools = new HashSet<>();
        ArrayDeque<ProcessSnapshot> pending = new ArrayDeque<>(tree.children(self));
        while (!pending.isEmpty()) {
            ProcessSnapshot tool = pending.pop();
            if (tools.add(tool.pid())) {
                pending.addAll(tree.children(tool));
            }
        }
        return processes.stream().filter(process -> !tools.contains(process.pid())).toList();
    }

    private static String keyOf(ProcessSnapshot process) {
        return process.name().toLowerCase(Locale.ROOT);
    }

    /** Une carte persistante : on réserve les systèmes existants avant d'insérer les nouveaux. */
    private static List<Mass> place(List<Mass> masses, Field previous) {
        Map<String, Mass> before = new HashMap<>();
        if (previous != null) {
            previous.masses().forEach(mass -> before.put(mass.key(), mass));
        }
        List<Mass> ordered = new ArrayList<>(masses);
        ordered.sort(Comparator.comparing((Mass mass) -> !before.containsKey(mass.key()))
                .thenComparing(Comparator.comparingLong(Mass::memoryBytes).reversed())
                .thenComparing(Mass::key));
        List<Mass> placed = new ArrayList<>();
        for (Mass mass : ordered) {
            Mass known = before.get(mass.key());
            double x = known == null ? 0 : known.homeX();
            double y = known == null ? 0 : known.homeY();
            // Une famille déjà sur la carte garde sa place tant que les territoires ne se
            // touchent pas réellement : l'espacement sert de marge quand un satellite s'ajoute.
            // Mesuré sur la machine réelle : en exigeant l'espacement complet, une vingtaine de
            // familles qui grandissaient à chaque relevé en chassaient d'autres, en cascade,
            // et 116 familles sur 118 sautaient de place toutes les trois secondes.
            double margin = known == null ? SPACING : 0;
            if (known != null && !roomAt(mass, x, y, placed, margin)) {
                // Gênée par une voisine qui a grandi : elle s'écarte juste assez, sans quitter
                // son quartier. Le rendu la fait glisser ; un saut à l'autre bout de la carte
                // se lisait comme un objet qui change de place sans raison.
                double[] nudged = nudge(mass, x, y, placed);
                if (nudged != null) {
                    x = nudged[0];
                    y = nudged[1];
                }
            }
            if (!roomAt(mass, x, y, placed, margin)) {
                // Spirale déterministe, sans forces ni intégration temporelle. Les petits
                // systèmes ne remplissent pas les espaces de respiration des grands.
                for (int slot = 1; ; slot++) {
                    double distance = 3_000 * Math.sqrt(slot);
                    double angle = slot * 2.399963229728653;
                    x = Math.cos(angle) * distance;
                    y = Math.sin(angle) * distance;
                    if (roomAt(mass, x, y, placed, SPACING)) {
                        break;
                    }
                }
            }
            placed.add(new Mass(mass.key(), mass.name(), mass.principal(), mass.anomalies(),
                    mass.processCount(), mass.memoryBytes(), mass.memoryKnown(), mass.memoryShare(),
                    mass.cpuShare(), x, y, mass.bodyRadius(), mass.systemRadius(), mass.wellDepth()));
        }
        placed.sort(Comparator.comparing(Mass::key));
        return placed;
    }

    /**
     * Le territoire d'une famille réserve la place de son horizon le plus grand possible, celui
     * d'une charge maximale. Une montée de CPU agrandit l'horizon à l'intérieur de cette place :
     * elle ne peut ni déplacer une famille, ni faire chevaucher deux horizons.
     */
    public static double territory(Mass mass) {
        return Math.max(TERRITORY_MIN, Math.max(Math.max(mass.systemRadius() * 1.38,
                mass.bodyRadius() * 2.8 + 1_400), horizonRadius(mass.bodyRadius(), 1) * 1.1));
    }

    /** En deçà de cette charge, pas d'horizon : l'espace autour d'une famille au repos reste calme. */
    public static final double HORIZON_THRESHOLD = .1;
    /** Ce que la charge ajoute à l'horizon, quelle que soit la taille : un petit processus actif se voit. */
    public static final double HORIZON_REACH = 6_000;

    /**
     * Le rayon de l'horizon noir d'une famille, en unités du monde : fixe à l'écran comme tout le
     * reste de la carte, il grandit et rétrécit avec le zoom.
     *
     * @param charge la charge ramenée entre 0 et 1
     */
    public static double horizonRadius(double bodyRadius, double charge) {
        if (charge < HORIZON_THRESHOLD) return 0;
        double c = Math.min(1, charge);
        return bodyRadius * (1.2 + 1.5 * c) + HORIZON_REACH * Math.pow(c, .8);
    }

    /** Le plus petit territoire : même un processus minuscule a la place de courber l'espace. */
    private static final double TERRITORY_MIN = 5_500;

    /**
     * Écarte une famille des voisines qu'elle touche, par petits pas le long de la direction
     * qui les sépare. Rend null si elle ne trouve pas de place libre en quelques pas : la
     * famille rejoint alors la spirale, comme une nouvelle venue.
     */
    private static double[] nudge(Mass mass, double x, double y, List<Mass> placed) {
        for (int step = 0; step < 24; step++) {
            Mass worst = null;
            double overlap = 0;
            for (Mass other : placed) {
                double gap = territory(mass) + territory(other) - Math.hypot(x - other.homeX(), y - other.homeY());
                if (gap > overlap) {
                    overlap = gap;
                    worst = other;
                }
            }
            if (worst == null) {
                return new double[] { x, y };
            }
            double dx = x - worst.homeX(), dy = y - worst.homeY();
            double length = Math.hypot(dx, dy);
            if (length < 1e-6) {
                dx = 1;
                dy = 0;
                length = 1;
            }
            // Un léger dépassement : on ne s'arrête pas pile au contact, à un arrondi près
            x += dx / length * (overlap + 50);
            y += dy / length * (overlap + 50);
        }
        return null;
    }

    private static boolean roomAt(Mass mass, double x, double y, List<Mass> placed, double margin) {
        for (Mass other : placed) {
            double minimum = territory(mass) + territory(other) + margin;
            if (Math.hypot(x - other.homeX(), y - other.homeY()) < minimum) {
                return false;
            }
        }
        return true;
    }

    /**
     * Le profil d'un entonnoir : sa hauteur à une distance donnée de son centre, négative.
     *
     * Une gorge adoucie en 1/√(1 + ρ²/gorge²), la forme d'un potentiel de gravitation qui ne
     * devient pas infini au centre ; multipliée par (1 − ρ²/portée²)², qui la ramène à plat
     * en douceur au bord de la portée, sans pli.
     */
    public static double wellHeight(double squaredDistance, Well well) {
        double reach = well.reach() * well.reach();
        if (squaredDistance >= reach || well.depth() == 0) {
            return 0;
        }
        double taper = 1 - squaredDistance / reach;
        return -well.depth() * taper * taper / Math.sqrt(1 + squaredDistance / (well.core() * well.core()));
    }

    /**
     * Où un point de la nappe au repos se retrouve une fois l'espace courbé.
     *
     * La hauteur est la somme des entonnoirs. L'attraction rapproche le point de chaque
     * centre le long du même rayon : r devient r · (1 − a·(1 − r²/portée²)²). Sa dérivée
     * reste au moins 1 − a, positive : la grille se resserre sans jamais se replier, et
     * deux points ne se rapprochent jamais de plus d'un facteur 1 − a.
     *
     * Écrit dans out : la position attirée, puis la hauteur.
     */
    public static void surface(double x, double y, List<Well> wells, double[] out) {
        surface(x, y, wells, 1, out);
    }

    /**
     * La même nappe, attirée plusieurs fois de suite. Chaque passe est une transformation sans
     * repli ; leur composition n'en crée pas davantage. n passes portent l'attraction au
     * centre jusqu'à 1 − 0,78ⁿ (63 % pour quatre) : c'est ce qui rend la courbure lisible vue
     * presque d'en haut. Réservé au dessin de la grille : les symboles gardent une seule passe, et avec elle
     * la garantie qu'aucun satellite n'en touche un autre.
     */
    public static void surface(double x, double y, List<Well> wells, int passes, double[] out) {
        double px = x;
        double py = y;
        double height = 0;
        for (Well well : wells) {
            double restX = x - well.x();
            double restY = y - well.y();
            height += wellHeight(restX * restX + restY * restY, well);
        }
        for (int pass = 0; pass < passes; pass++) {
            for (Well well : wells) {
                if (well.pull() == 0) {
                    continue;
                }
                double dx = px - well.x();
                double dy = py - well.y();
                double squared = dx * dx + dy * dy;
                double reach = well.reach() * well.reach();
                if (squared >= reach) {
                    continue;
                }
                double taper = 1 - squared / reach;
                double pull = well.pull() * taper * taper;
                px = well.x() + dx * (1 - pull);
                py = well.y() + dy * (1 - pull);
            }
        }
        out[0] = px;
        out[1] = py;
        out[2] = height;
    }

    /**
     * Hauteur du centre d'un corps posé dans son propre entonnoir.
     *
     * Une sphère ne traverse pas la nappe : elle descend jusqu'à toucher les parois. Pour
     * chaque distance ρ sous la sphère, sa surface doit rester au-dessus de la nappe ; le
     * centre se pose donc à la plus haute des contraintes. À plat, il repose à un rayon
     * au-dessus ; dans une gorge étroite, les parois le retiennent plus haut.
     */
    public static double restingLift(double radius, Well well) {
        double bottom = wellHeight(0, well);
        double lift = radius;
        for (int i = 1; i <= 16; i++) {
            double rho = radius * i / 16;
            double rise = wellHeight(rho * rho, well) - bottom;
            lift = Math.max(lift, rise + Math.sqrt(Math.max(0, radius * radius - rho * rho)));
        }
        return lift;
    }

    /**
     * Index spatial des puits, reconstruit à chaque image. Chaque point de la grille n'est
     * confronté qu'aux puits de sa case, au lieu de tous : sans cela, des centaines de
     * puits multipliés par des milliers de points dépassaient le temps d'une image.
     *
     * Les puits sont inscrits sur une zone élargie : un point déjà attiré par un premier
     * puits peut entrer dans la portée du suivant, et doit encore le trouver dans sa case.
     */
    public static final class WellIndex {
        private static final List<Well> NONE = List.of();

        private final double cell;
        private final Map<Long, List<Well>> cells = new HashMap<>();

        public WellIndex(List<Well> wells, double cell, double minX, double minY, double maxX, double maxY) {
            this.cell = Math.max(1, cell);
            // Seuls les puits qui attirent déplacent les points : c'est leur portée qui fixe la marge
            double widest = 0;
            for (Well well : wells) {
                if (well.pull() > 0) widest = Math.max(widest, well.reach());
            }
            // Une grille attirée en quatre passes peut entraîner un point jusqu'à 63 % de la portée
            double slack = widest * 0.7;
            for (Well well : wells) {
                // Un puits qui n'attire pas ne compte qu'au point de repos : il n'a besoin d'aucune
                // marge. Mesuré : avec la marge pour tous, chaque amas de matière noire s'inscrivait
                // sur 32 000 unités autour de lui, et une image prenait deux fois plus de temps.
                double reach = well.reach() + (well.pull() > 0 ? slack : 0);
                if (well.x() + reach < minX || well.x() - reach > maxX
                        || well.y() + reach < minY || well.y() - reach > maxY) {
                    continue;
                }
                long x0 = cellOf(Math.max(minX, well.x() - reach));
                long x1 = cellOf(Math.min(maxX, well.x() + reach));
                long y0 = cellOf(Math.max(minY, well.y() - reach));
                long y1 = cellOf(Math.min(maxY, well.y() + reach));
                for (long cx = x0; cx <= x1; cx++) {
                    for (long cy = y0; cy <= y1; cy++) {
                        cells.computeIfAbsent(key(cx, cy), ignored -> new ArrayList<>()).add(well);
                    }
                }
            }
        }

        public List<Well> near(double x, double y) {
            return cells.getOrDefault(key(cellOf(x), cellOf(y)), NONE);
        }

        private long cellOf(double value) {
            return (long) Math.floor(value / cell);
        }

        private static long key(long cx, long cy) {
            return (cx << 32) ^ (cy & 0xFFFFFFFFL);
        }
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
