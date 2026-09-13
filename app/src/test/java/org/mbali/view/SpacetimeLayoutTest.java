package org.mbali.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mbali.model.ProcessSnapshot;
import org.mbali.view.SpacetimeLayout.Field;
import org.mbali.view.SpacetimeLayout.Mass;
import org.mbali.view.SpacetimeLayout.Well;

class SpacetimeLayoutTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private static final long GIGABYTE = 1L << 30;

    private static ProcessSnapshot process(long pid, long parent, String name, long memoryBytes,
                                           Duration cpu, Duration age) {
        return new ProcessSnapshot(pid, OptionalLong.of(parent), name, Optional.of(NOW.minus(age)),
                Optional.of(cpu), OptionalLong.of(memoryBytes), Optional.empty());
    }

    private static ProcessSnapshot light(long pid, long parent, String name, Duration age) {
        return process(pid, parent, name, 50_000_000L, Duration.ZERO, age);
    }

    private static Field field(List<ProcessSnapshot> processes) {
        return SpacetimeLayout.compute(processes, OptionalLong.of(16 * GIGABYTE), 4, NOW, OptionalLong.empty());
    }

    private static Mass mass(Field field, String key) {
        return field.masses().stream().filter(mass -> mass.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void theInstancesOfOneApplicationBecomeOneMassWithItsAnomalies() {
        List<ProcessSnapshot> processes = List.of(
                light(100, 1, "explorer.exe", Duration.ofHours(2)),
                light(200, 100, "chrome.exe", Duration.ofMinutes(60)),
                light(201, 200, "chrome.exe", Duration.ofMinutes(50)),
                light(202, 200, "chrome.exe", Duration.ofMinutes(50)));

        Field field = field(processes);
        Mass chrome = mass(field, "chrome.exe");

        assertEquals(2, field.masses().size(), "explorer et chrome : deux applications");
        assertEquals(3, chrome.processCount());
        assertEquals(200, chrome.principal().pid(), "le principal est celui qu'explorer a lancé");
        assertEquals(Set.of(201L, 202L),
                chrome.anomalies().stream().map(a -> a.process().pid()).collect(Collectors.toSet()));
    }

    @Test
    void aSubProcessIsNeverThePrincipalEvenWithASmallerPid() {
        // À âge égal, le plus petit PID gagnerait : le sous-processus doit pourtant être écarté
        List<ProcessSnapshot> processes = List.of(
                light(100, 1, "explorer.exe", Duration.ofHours(2)),
                light(900, 100, "chrome.exe", Duration.ofMinutes(10)),
                light(150, 900, "chrome.exe", Duration.ofMinutes(10)));

        assertEquals(900, mass(field(processes), "chrome.exe").principal().pid());
    }

    @Test
    void memoryDecidesTheAreaNotTheRadius() {
        // Quatre fois plus de mémoire : deux fois plus de rayon, donc quatre fois plus de surface
        double small = SpacetimeLayout.bodyRadius(0.04);
        double large = SpacetimeLayout.bodyRadius(0.16);

        assertEquals(2, large / small, 1e-9);
    }

    @Test
    void theMemoryShareIsAPercentageOfTheWholeMachine() {
        Field field = field(List.of(process(10, 1, "java.exe", 4 * GIGABYTE, Duration.ZERO, Duration.ofHours(1))));

        assertEquals(0.25, mass(field, "java.exe").memoryShare(), 1e-9, "4 Go sur 16 Go");
    }

    @Test
    void anUnknownMemoryIsNotPretendedToBeKnown() {
        ProcessSnapshot secret = new ProcessSnapshot(4, OptionalLong.of(0), "System",
                Optional.empty(), Optional.empty(), OptionalLong.empty(), Optional.empty());

        Mass system = mass(field(List.of(secret)), "system");

        assertFalse(system.memoryKnown());
        assertEquals(0, system.memoryBytes());
    }

    @Test
    void theIdleProcessDoesNotCurveSpace() {
        // Mesuré : son temps CPU est énorme, mais c'est du temps libre, pas une consommation
        ProcessSnapshot idle = process(0, 0, "System Idle Process", 8_192, Duration.ofHours(50), Duration.ofHours(6));

        Mass mass = mass(field(List.of(idle)), "system idle process");

        assertEquals(0, mass.cpuShare(), 1e-12);
        assertEquals(0, mass.wellDepth(), 1e-12);
    }

    @Test
    void curvatureIsMeasuredInCoresSoRealUsageIsVisible() {
        // Mesuré sur la machine réelle (12 cœurs) : rapporté à toute la machine, le puits le
        // plus profond ne dépassait pas 0,13, et la courbure restait plate.
        double tenthOfACore = SpacetimeLayout.wellDepth(0.1 / 12, 12);
        double oneCore = SpacetimeLayout.wellDepth(1.0 / 12, 12);
        double everyCore = SpacetimeLayout.wellDepth(1.0, 12);

        assertTrue(tenthOfACore > 0.2, "un dixième de cœur doit se voir : " + tenthOfACore);
        assertTrue(oneCore > tenthOfACore * 1.5, "un cœur entier creuse franchement");
        assertTrue(everyCore > oneCore, "plusieurs cœurs se distinguent encore d'un seul");
        assertTrue(everyCore <= SpacetimeLayout.MAX_WELL_DEPTH, "sans jamais dépasser la profondeur maximale");
    }

    @Test
    void aBusierProcessDeepensItsWellWithoutChangingItsTerritory() {
        Well quiet = SpacetimeLayout.wellOf(0, 0, 500, 3_000, SpacetimeLayout.wellDepth(.01, 4));
        Well busy = SpacetimeLayout.wellOf(0, 0, 500, 3_000, SpacetimeLayout.wellDepth(.5, 4));
        assertEquals(quiet.reach(), busy.reach());
        assertTrue(SpacetimeLayout.wellHeight(500 * 500, busy) < SpacetimeLayout.wellHeight(500 * 500, quiet));
    }

    @Test
    void theAverageCpuAccountsForEveryCore() {
        // 30 minutes de CPU sur une heure de vie, réparties sur 2 cœurs : un quart de la machine
        ProcessSnapshot busy = process(42, 1, "encoder.exe", 1, Duration.ofMinutes(30), Duration.ofHours(1));

        assertEquals(0.25, SpacetimeLayout.averageCpuShare(busy, 2, NOW), 1e-9);
    }

    @Test
    void evenAFullCpuNeverFoldsTheGrid() {
        // Une profondeur demandée absurde est bornée : la garantie ne dépend pas de l'appelant
        Well greedy = new Well(0, 0, 7_000, 800, 3_500, 5);
        assertEquals(SpacetimeLayout.MAX_PULL, greedy.pull());

        double[] out = new double[3];
        double previous = -1;
        for (double r = 0; r <= 3_500; r += 5) {
            SpacetimeLayout.surface(r, 0, List.of(greedy), out);
            assertTrue(out[0] > previous, "la grille s'est repliée à r = " + r);
            previous = out[0];
        }

        // Deux puits profonds qui se chevauchent : les points d'une même ligne gardent leur ordre
        List<Well> overlapping = List.of(new Well(0, 0, 7_000, 800, 3_500, 5),
                new Well(1_500, 0, 7_000, 900, 4_000, 5));
        previous = Double.NEGATIVE_INFINITY;
        for (double x = -4_000; x <= 6_000; x += 5) {
            SpacetimeLayout.surface(x, 0, overlapping, out);
            assertTrue(out[0] > previous, "croisement de lignes entre deux puits à x = " + x);
            previous = out[0];
        }
    }

    @Test
    void aWellLeavesItsCentreInPlaceAndFarSpaceUntouched() {
        Well well = new Well(500, -300, 800, 500, 3_000, 0.2);
        double[] out = new double[3];

        SpacetimeLayout.surface(500, -300, List.of(well), out);
        assertEquals(500, out[0], 1e-9);
        assertEquals(-300, out[1], 1e-9);

        SpacetimeLayout.surface(500 + 10_000, -300, List.of(well), out);
        assertEquals(500 + 10_000, out[0], 1e-9, "hors de portée, l'espace reste plat");
        assertEquals(0, out[2], 1e-12);
    }

    @Test
    void massesNeverOverlap() {
        List<ProcessSnapshot> processes = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 40; i++) {
            processes.add(process(1_000 + i, 1, "app" + i + ".exe",
                    (long) (random.nextDouble() * 3 * GIGABYTE), Duration.ZERO, Duration.ofHours(1)));
        }
        List<Mass> masses = field(processes).masses();

        for (int i = 0; i < masses.size(); i++) {
            for (int j = i + 1; j < masses.size(); j++) {
                Mass a = masses.get(i);
                Mass b = masses.get(j);
                double distance = Math.hypot(a.homeX() - b.homeX(), a.homeY() - b.homeY());
                assertTrue(distance >= SpacetimeLayout.territory(a) + SpacetimeLayout.territory(b),
                        a.name() + " et " + b.name() + " se chevauchent");
            }
        }
    }

    @Test
    void placementIsDeterministicSoTheMapNeverJumps() {
        List<ProcessSnapshot> processes = List.of(
                light(10, 1, "code.exe", Duration.ofHours(1)),
                light(11, 1, "chrome.exe", Duration.ofHours(1)),
                light(12, 1, "java.exe", Duration.ofHours(1)));

        List<Mass> first = field(processes).masses();
        List<Mass> second = field(processes).masses();

        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).homeX(), second.get(i).homeX());
            assertEquals(first.get(i).homeY(), second.get(i).homeY());
        }
    }

    @Test
    void mbaliscopesOwnMeasuringToolsAreLeftOut() {
        // Sans cela, le PowerShell lancé pour mesurer apparaîtrait comme une masse consommant du CPU
        List<ProcessSnapshot> processes = List.of(
                light(100, 1, "explorer.exe", Duration.ofHours(2)),
                light(500, 100, "java.exe", Duration.ofMinutes(30)),
                light(600, 500, "powershell.exe", Duration.ofSeconds(1)),
                light(601, 600, "conhost.exe", Duration.ofSeconds(1)),
                light(700, 100, "powershell.exe", Duration.ofMinutes(5)));

        Field field = SpacetimeLayout.compute(processes, OptionalLong.of(16 * GIGABYTE), 4, NOW, OptionalLong.of(500));

        assertEquals(3, field.processCount(), "explorer, MbaliScope lui-même, et le PowerShell de l'utilisateur");
        assertEquals(700, mass(field, "powershell.exe").principal().pid());
        assertTrue(field.masses().stream().noneMatch(mass -> mass.key().equals("conhost.exe")));
        assertEquals(1, mass(field, "java.exe").processCount(), "MbaliScope reste visible : il consomme vraiment");
    }

    @Test
    void theSpatialIndexGivesExactlyTheSameWarpAsTheFullList() {
        Random random = new Random(11);
        List<Well> wells = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            wells.add(new Well(random.nextDouble() * 20_000 - 10_000, random.nextDouble() * 20_000 - 10_000,
                    random.nextDouble() * 7_000, 300 + random.nextDouble() * 1_000,
                    1_500 + random.nextDouble() * 2_500, random.nextDouble() * .22));
        }
        SpacetimeLayout.WellIndex index =
                new SpacetimeLayout.WellIndex(wells, 2_000, -20_000, -20_000, 20_000, 20_000);

        double[] full = new double[3];
        double[] indexed = new double[3];
        for (int i = 0; i < 4_000; i++) {
            double x = random.nextDouble() * 24_000 - 12_000;
            double y = random.nextDouble() * 24_000 - 12_000;
            SpacetimeLayout.surface(x, y, wells, full);
            SpacetimeLayout.surface(x, y, index.near(x, y), indexed);
            assertEquals(full[0], indexed[0], 1e-9, "écart en x au point " + x + ", " + y);
            assertEquals(full[1], indexed[1], 1e-9, "écart en y au point " + x + ", " + y);
        }
    }

    @Test
    void currentCpuUsesTheIntervalAndRejectsRecycledPids() {
        ProcessSnapshot first = process(42, 1, "encoder.exe", GIGABYTE, Duration.ofHours(1), Duration.ofDays(10));
        ProcessSnapshot next = new ProcessSnapshot(first.pid(), first.parentPid(), first.name(), first.startTime(),
                Optional.of(first.cpuTime().orElseThrow().plusSeconds(6)), first.memoryBytes(), first.user());
        assertEquals(.5, SpacetimeLayout.cpuShare(next, first, 4, 3), 1e-12);
        assertEquals(0, SpacetimeLayout.cpuShare(first, next, 4, 3));
        assertEquals(0, SpacetimeLayout.cpuShare(next, null, 4, 3));
        ProcessSnapshot recycled = process(42, 1, "encoder.exe", GIGABYTE, Duration.ofSeconds(100), Duration.ofSeconds(200));
        assertEquals(0, SpacetimeLayout.cpuShare(recycled, first, 4, 3));
    }

    @Test
    void refreshingAndAddingAnUnrelatedProcessNeverMovesExistingSystems() {
        List<ProcessSnapshot> samples = new ArrayList<>();
        for (int i = 0; i < 40; i++) samples.add(light(1_000 + i, 1, "app" + i, Duration.ofHours(1)));
        Field first = field(samples);
        samples.add(light(3_000, 1, "new.exe", Duration.ofMinutes(1)));
        Field second = SpacetimeLayout.compute(samples, first.totalMemoryBytes(), 4, NOW.plusSeconds(3), OptionalLong.empty(), first);
        for (Mass old : first.masses()) {
            Mass next = mass(second, old.key());
            assertEquals(old.homeX(), next.homeX());
            assertEquals(old.homeY(), next.homeY());
        }
        assertTrue(second.cpuSampled());
        assertFalse(first.cpuSampled());
    }

    @Test
    void insertingAChildKeepsEveryExistingOrbitalSlot() {
        List<ProcessSnapshot> samples = new ArrayList<>();
        for (int i = 0; i < 24; i++) samples.add(light(100 + i, i == 0 ? 1 : 100, "chrome.exe", Duration.ofMinutes(60 - i)));
        Field first = field(samples);
        samples.add(light(900, 100, "chrome.exe", Duration.ofSeconds(10)));
        Field second = SpacetimeLayout.compute(samples, first.totalMemoryBytes(), 4, NOW.plusSeconds(3), OptionalLong.empty(), first);
        Mass old = mass(first, "chrome.exe"), next = mass(second, "chrome.exe");
        for (var a : old.anomalies()) {
            var b = next.anomalies().stream().filter(item -> item.process().pid() == a.process().pid()).findFirst().orElseThrow();
            assertEquals(a.orbitRadius(), b.orbitRadius());
            assertEquals(a.slotAngle(), b.slotAngle());
            assertEquals(a.ring(), b.ring());
        }
    }

    @Test
    void aHundredSatellitesNeverCollideAtAnyInclinationOrCpuLoad() {
        List<ProcessSnapshot> samples = new ArrayList<>();
        Random random = new Random(39);
        for (int i = 0; i < 101; i++) samples.add(process(100 + i, i == 0 ? 1 : 100, "browser.exe",
                2_000_000 + (long) (random.nextDouble() * 100_000_000), Duration.ZERO, Duration.ofMinutes(120 - i)));
        Mass mass = mass(field(samples), "browser.exe");
        for (double depth : new double[] {0, 3_000, SpacetimeLayout.MAX_WELL_DEPTH}) {
            Well well = SpacetimeLayout.wellOf(0, 0, mass.bodyRadius(), mass.systemRadius(), depth);
            for (double t = 0; t < 600; t += 2.7) {
                List<double[]> centres = new ArrayList<>();
                for (var a : mass.anomalies()) {
                    double angle = a.slotAngle() + SpacetimeLayout.angularSpeed(a.orbitRadius(), 0) * t;
                    double[] p = new double[3];
                    SpacetimeLayout.surface(Math.cos(angle) * a.orbitRadius(), Math.sin(angle) * a.orbitRadius(), List.of(well), p);
                    p[1] *= Math.sin(Perspective.MIN_ELEVATION);
                    assertTrue(Math.hypot(p[0], p[1]) > mass.bodyRadius() * 1.3 + a.radius(), "collision avec le noyau");
                    centres.add(p);
                }
                for (int i = 0; i < centres.size(); i++) for (int j = i + 1; j < centres.size(); j++) {
                    double distance = Math.hypot(centres.get(i)[0] - centres.get(j)[0], centres.get(i)[1] - centres.get(j)[1]);
                    assertTrue(distance > mass.anomalies().get(i).radius() + mass.anomalies().get(j).radius(), "collision de satellites à t=" + t);
                }
            }
        }
    }

    @Test
    void noOrbitCanBecomeAHighSpeedSpinner() {
        for (double radius : new double[] {60, 500, 3_000, 12_000, 30_000}) {
            assertTrue(2 * Math.PI / SpacetimeLayout.angularSpeed(radius, 7_000) >= 140 - 1e-9);
        }
    }

    @Test
    void evenAtFullLoadTwoHorizonsNeverOverlap() {
        // Les horizons grandissent avec le CPU : leur place doit être réservée d'avance, sinon une
        // montée de charge ferait chevaucher deux familles ou obligerait la carte à bouger
        List<ProcessSnapshot> samples = new ArrayList<>();
        Random random = new Random(5);
        for (int i = 0; i < 40; i++) {
            samples.add(process(1_000 + i, 1, "app" + i + ".exe", (long) (random.nextDouble() * 2 * GIGABYTE),
                    Duration.ZERO, Duration.ofHours(1)));
        }
        List<Mass> masses = field(samples).masses();
        for (int i = 0; i < masses.size(); i++) {
            for (int j = i + 1; j < masses.size(); j++) {
                Mass a = masses.get(i), b = masses.get(j);
                double distance = Math.hypot(a.homeX() - b.homeX(), a.homeY() - b.homeY());
                assertTrue(distance >= SpacetimeLayout.horizonRadius(a.bodyRadius(), 1)
                        + SpacetimeLayout.horizonRadius(b.bodyRadius(), 1),
                        a.name() + " et " + b.name() + " : leurs horizons se chevaucheraient");
            }
        }
        assertEquals(0, SpacetimeLayout.horizonRadius(500, SpacetimeLayout.HORIZON_THRESHOLD / 2),
                "au repos, pas d'horizon");
        assertTrue(SpacetimeLayout.horizonRadius(60, 1) > SpacetimeLayout.HORIZON_REACH,
                "même un processus minuscule ouvre un grand horizon quand il consomme");
    }

    @Test
    void aFamilyWithMixedSizesKeepsEveryPlaceReadingAfterReading() {
        // Reproduit code.exe sur la machine réelle : des satellites de tailles très différentes,
        // qui grossissent un peu à chaque relevé. Un anneau tout juste construit ne doit jamais
        // être jugé trop serré au relevé suivant, sinon ses satellites fuient vers l'extérieur.
        Random random = new Random(17);
        long[] memory = new long[18];
        for (int i = 0; i < memory.length; i++) memory[i] = 30_000_000L + (long) (random.nextDouble() * 900_000_000L);
        Field current = null;
        Mass first = null;
        for (int reading = 0; reading < 12; reading++) {
            List<ProcessSnapshot> samples = new ArrayList<>();
            samples.add(process(10, 1, "neighbour.exe", 200_000_000L, Duration.ZERO, Duration.ofHours(3)));
            for (int i = 0; i < memory.length; i++) {
                long grown = (long) (memory[i] * (1 + reading * 0.004));
                samples.add(process(100 + i, i == 0 ? 1 : 100, "code.exe", grown, Duration.ZERO, Duration.ofMinutes(90 - i)));
            }
            current = SpacetimeLayout.compute(samples, OptionalLong.of(16 * GIGABYTE), 4, NOW.plusSeconds(3L * reading),
                    OptionalLong.empty(), current);
            Mass code = mass(current, "code.exe");
            if (first == null) {
                first = code;
                continue;
            }
            // Les satellites grossissent un peu : le bord du système suit leur rayon, pas davantage.
            // Une fuite vers un anneau extérieur ajouterait des milliers d'unités d'un coup.
            assertEquals(first.systemRadius(), code.systemRadius(), first.systemRadius() * .01,
                    "la famille a grossi au relevé " + reading);
            assertEquals(first.homeX(), code.homeX(), "la famille a changé de place au relevé " + reading);
            for (var a : first.anomalies()) {
                var b = code.anomalies().stream().filter(item -> item.process().pid() == a.process().pid()).findFirst().orElseThrow();
                assertEquals(a.orbitRadius(), b.orbitRadius(), "le satellite " + a.process().pid() + " a changé d'anneau");
                assertEquals(a.slotAngle(), b.slotAngle());
            }
        }
    }

    @Test
    void processChurnReusesVacanciesInsteadOfExpandingTheSystemForever() {
        List<ProcessSnapshot> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) samples.add(light(100 + i, i == 0 ? 1 : 100, "chrome.exe", Duration.ofMinutes(60 - i)));
        Field current = field(samples);
        double radius = mass(current, "chrome.exe").systemRadius();
        for (int generation = 0; generation < 60; generation++) {
            samples.remove(1);
            samples.add(light(1000 + generation, 100, "chrome.exe", Duration.ofSeconds(1)));
            current = SpacetimeLayout.compute(samples, current.totalMemoryBytes(), 4,
                    NOW.plusSeconds(3L * (generation + 1)), OptionalLong.empty(), current);
            assertEquals(radius, mass(current, "chrome.exe").systemRadius(), 1e-8,
                    "les places des processus terminés doivent être réutilisées");
        }
    }
}
