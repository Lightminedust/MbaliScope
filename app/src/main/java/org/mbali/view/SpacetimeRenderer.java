package org.mbali.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.mbali.model.ProcessSnapshot;
import org.mbali.view.SpacetimeLayout.Anomaly;
import org.mbali.view.SpacetimeLayout.Field;
import org.mbali.view.SpacetimeLayout.Mass;
import org.mbali.view.SpacetimeLayout.Well;
import org.mbali.view.SpacetimeLayout.WellIndex;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * L'espace-temps des processus, dessiné en particules et en traits fins.
 *
 * Les principes ne changent pas, seule la matière change :
 *   — chaque famille est une sphère pleine de particules en mouvement, à l'échelle de sa mémoire ;
 *   — le CPU mesuré se voit sur la famille, quelle que soit sa taille : ses particules changent de
 *     couleur et accélèrent, et un horizon noir s'ouvre autour d'elle, d'autant plus grand que la
 *     charge est haute. Cet horizon courbe la lumière comme une lentille : la poussière de
 *     l'espace se tord et s'amasse sur son bord ;
 *   — la poussière de l'espace dérive au hasard, et les familles actives l'aspirent ;
 *   — les sous-processus sont de petites sphères de quatre formes, selon leur CPU en pourcentage
 *     d'un cœur, sur des orbites qui ne se croisent jamais ;
 *   — les liens sont des traits fins qui s'allument et s'éteignent. Ils sont simulés, comme les
 *     paquets de rencontre : ils ne mesurent aucun échange réel.
 *
 * Tout est tracé à la résolution de l'écran : la carte reste nette de près comme de loin. Les
 * particules sont regroupées par teinte et opacité, et dessinées carré par carré, ce que la carte
 * graphique fait sans effort.
 */
public final class SpacetimeRenderer {
    private static final Color INK = Color.web("#040407");
    private static final Color TEXT = Color.web("#f2f2f5");
    private static final Color MUTED = Color.web("#8c8c98");
    private static final Color FAINT = Color.web("#3a3a44");
    private static final Color ACCENT = Color.web("#ff2d55");

    /** Les quatre couleurs de la charge : repos, modérée, soutenue, intense. */
    private static final Color[] STAGES = {
            Color.web("#3fe6ff"), Color.web("#7a5cff"), Color.web("#ff3dc8"), Color.web("#ff6a2b") };
    private static final String[] STAGE_NAMES_FR = { "REPOS", "MODÉRÉE", "SOUTENUE", "INTENSE" };
    private static final String[] STAGE_NAMES_EN = { "IDLE", "MODERATE", "HIGH", "INTENSE" };

    private static final Font DISPLAY = Font.font("Segoe UI", FontWeight.BOLD, 21);
    private static final Font LABEL = Font.font("Segoe UI", FontWeight.BOLD, 11.5);
    private static final Font CAPTION = Font.font("Segoe UI", 9.5);
    private static final Font MICRO = Font.font("Segoe UI", FontWeight.BOLD, 9);

    private static final double TAU = Math.PI * 2;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
    private static final int MAX_PACKETS = 32;
    /** Passes d'attraction de l'espace : vue presque d'en haut, c'est le pincement qui montre la courbure. */
    private static final int SHEET_PASSES = 4;
    /**
     * Assez de poussière pour que l'horizon se voie : sur un fond noir, un trou noir n'existe que
     * par la matière qu'il repousse sur son bord. Mesuré sur les captures : avec une poussière
     * lâche et pâle, seul l'anneau de l'horizon se distinguait.
     */
    private static final double DUST_PX = 15;
    private static final int MAX_DUST = 12_000;
    private static final double HORIZON_THRESHOLD = SpacetimeLayout.HORIZON_THRESHOLD;

    /** Particules regroupées par teinte et par opacité. */
    private static final int HUES = 48;
    private static final int LEVELS = 6;
    private static final Color[][] SHADES = new Color[HUES + 1][LEVELS];

    static {
        for (int h = 0; h <= HUES; h++) {
            Color base = h == HUES ? Color.web("#9aa3b8") : stage(h / (double) (HUES - 1));
            for (int l = 0; l < LEVELS; l++) {
                SHADES[h][l] = base.deriveColor(0, 1, 1, (l + 1) / (double) LEVELS);
            }
        }
    }

    private static final class Particles {
        final double[][] data = new double[(HUES + 1) * LEVELS][192];
        final int[] counts = new int[(HUES + 1) * LEVELS];

        /** @param hue la charge représentée, entre 0 et 1, ou négative pour la poussière grise */
        void add(double x, double y, double size, double hue, double alpha) {
            if (alpha < .5 / LEVELS) return;
            int h = hue < 0 ? HUES : (int) Math.round(Math.max(0, Math.min(1, hue)) * (HUES - 1));
            int l = (int) Math.min(LEVELS - 1, Math.max(0, Math.round(alpha * LEVELS - 1)));
            int bucket = h * LEVELS + l;
            double[] values = data[bucket];
            int count = counts[bucket];
            if (count + 3 > values.length) {
                values = Arrays.copyOf(values, values.length * 2);
                data[bucket] = values;
            }
            values[count] = x;
            values[count + 1] = y;
            values[count + 2] = size;
            counts[bucket] = count + 3;
        }

        void flush(GraphicsContext gc) {
            for (int bucket = 0; bucket < counts.length; bucket++) {
                int count = counts[bucket];
                if (count == 0) continue;
                double[] values = data[bucket];
                // Un carré par particule, sans chemin : réunis en un seul chemin, les mêmes carrés
                // étaient rastérisés en logiciel et coûtaient plus que tout le reste de l'image
                gc.setFill(SHADES[bucket / LEVELS][bucket % LEVELS]);
                for (int i = 0; i < count; i += 3) {
                    double s = values[i + 2];
                    gc.fillRect(values[i] - s / 2, values[i + 1] - s / 2, s, s);
                }
                counts[bucket] = 0;
            }
        }
    }

    /** Une forme de sphère : ses sommets sur la sphère unité et ses arêtes, par paires d'indices. */
    private record Mesh(double[][] vertices, int[] edges) { }

    private static final Mesh GEODESIC;
    private static final Mesh GEODESIC_FINE;
    private static final Mesh CAGE;

    static {
        List<double[]> vertices = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        icosahedron(vertices, faces);
        List<int[]> level1 = subdivide(vertices, faces);
        GEODESIC = new Mesh(vertices.toArray(double[][]::new), edgesOf(level1));
        CAGE = dualOf(vertices, level1);
        List<double[]> fineVertices = new ArrayList<>(vertices);
        List<int[]> level2 = subdivide(fineVertices, level1);
        GEODESIC_FINE = new Mesh(fineVertices.toArray(double[][]::new), edgesOf(level2));
    }

    private static final Map<Integer, double[][]> SPHERE_POINTS = new HashMap<>();
    private static final Map<Integer, int[]> PLEXUS = new HashMap<>();

    private static final class Body {
        Mass mass;
        double alpha;
        double depth;
        /** La charge lissée : horizon et couleurs glissent d'un relevé à l'autre au lieu de sauter. */
        double charge;
        boolean present = true;
        final Map<Integer, Double> phases = new HashMap<>();
        final Map<String, Double> appearances = new HashMap<>();
        /** Écart entre la place affichée et la nouvelle place : il fond, le corps glisse. */
        double shiftX, shiftY;
        final Map<String, double[]> satelliteShifts = new HashMap<>();
        /** Visibilité de chaque lien, qui apparaît et disparaît en fondu. */
        final Map<String, Double> links = new HashMap<>();
        Well well;
        Body(Mass mass) { this.mass = mass; }
    }

    /**
     * @param activity    charge ramenée entre 0 et 1 : deux cœurs pleins la saturent
     * @param corePercent CPU en pourcentage d'un cœur, qui choisit la forme d'un sous-processus
     */
    private record Point(String id, String owner, ProcessSnapshot process, double x, double y,
                         double radius, double worldX, double worldY, double alpha, int ring,
                         double activity, double corePercent) { }
    /** Un horizon : là où la charge d'une famille ouvre un trou noir qui courbe la lumière. */
    private record Lens(String owner, double x, double y, double horizon, double charge) { }
    private record Packet(String from, String to, double start) { }
    private record Box(double x, double y, double width, double height) {
        boolean hits(Box b) {
            return x < b.x + b.width && x + width > b.x && y < b.y + b.height && y + height > b.y;
        }
    }

    private final Map<String, Body> bodies = new LinkedHashMap<>();
    private final Map<String, Point> points = new LinkedHashMap<>();
    private final List<Lens> lenses = new ArrayList<>();
    private final List<Box> labels = new ArrayList<>();
    private final List<Packet> packets = new ArrayList<>();
    private final Map<String, Boolean> encounters = new HashMap<>();
    private final double[] surface = new double[3];
    private final double[] projected = new double[4];
    private final double[] bent = new double[2];
    private final double[] turned = new double[3];
    private final double[] meshX = new double[200];
    private final double[] meshY = new double[200];
    private final double[] meshZ = new double[200];
    private final double[] stars = new double[420];
    private final Particles space = new Particles();
    private final Particles matter = new Particles();
    private Field lastField;
    private double time;
    private double zoom = 1;
    /** Bas du panneau de droite : les légendes de la carte ne passent pas dessous. */
    private double panelBottom = 440;
    private double frameDt;
    private double nextEncounterCheck;
    private double elevation = Math.toRadians(72);
    private boolean paused;
    private UiLanguage language = UiLanguage.FRENCH;

    public SpacetimeRenderer() {
        Random random = new Random(20260913);
        for (int i = 0; i < stars.length; i++) stars[i] = random.nextDouble();
    }
    public void setPaused(boolean paused) { this.paused = paused; }
    public boolean paused() { return paused; }
    public void setLanguage(UiLanguage language) {
        this.language = language == null ? UiLanguage.FRENCH : language;
    }
    private String text(String french, String english) { return language.text(french, english); }
    public void tilt(double delta) {
        elevation = Math.max(Perspective.MIN_ELEVATION,
                Math.min(Perspective.MAX_ELEVATION, elevation + delta * .003));
    }
    public double[] toCameraScreen(Camera camera, double x, double y, double width, double height) {
        double[] ground = Perspective.of(camera, width, height, elevation).ground(x, y);
        return new double[] { camera.screenX(ground[0]), camera.screenY(ground[1]) };
    }
    public void zoomAt(Camera camera, double x, double y, double factor, double width, double height) {
        double[] anchor = toCameraScreen(camera, x, y, width, height);
        camera.zoomAt(anchor[0], anchor[1], factor);
    }
    public void pan(Camera camera, double dx, double dy) { camera.pan(dx, dy / Math.sin(elevation)); }

    /** Cadrage lisible des principaux systèmes ; les petits restent dans le champ navigable. */
    public void frame(Camera camera, Field field, double width, double height, boolean overview) {
        if (field == null || field.masses().isEmpty()) return;
        List<Mass> ordered = field.masses().stream()
                .sorted(Comparator.comparingLong(Mass::memoryBytes).reversed()).toList();
        List<Mass> framed = overview ? ordered : ordered.subList(0, Math.min(2, ordered.size()));
        double minX = Double.POSITIVE_INFINITY, minY = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (Mass mass : framed) {
            double radius = Math.max(mass.systemRadius() * 1.12, mass.bodyRadius() * 1.4);
            minX = Math.min(minX, mass.homeX() - radius);
            maxX = Math.max(maxX, mass.homeX() + radius);
            minY = Math.min(minY, mass.homeY() - radius);
            maxY = Math.max(maxY, mass.homeY() + radius);
        }
        camera.fit(minX, minY, maxX, maxY, width, height, 120);
    }
    public boolean focus(Camera camera, Field field, String id, double width, double height) {
        if (field == null || id == null) return false;
        for (Mass mass : field.masses()) {
            if (id.equals("mass:" + mass.key()) || matches(mass, id)
                    || mass.anomalies().stream().anyMatch(a -> processId(a.process()).equals(id))) {
                camera.centerOn(mass.homeX(), mass.homeY(), Math.max(mass.systemRadius() * 1.22, 4_500),
                        width, height, 125);
                return true;
            }
        }
        return false;
    }

    public void draw(GraphicsContext gc, Field field, Camera camera, double width, double height,
                     double seconds, double elapsed, double mouseX, double mouseY, boolean mouseInside,
                     String selectedId, String query, String status) {
        if (width < 2 || height < 2) return;
        double dt = paused ? 0 : Math.max(0, Math.min(.1, elapsed));
        frameDt = dt;
        time += dt;
        update(field, dt);
        Perspective view = Perspective.of(camera, width, height, elevation);
        zoom = view.zoom();
        gc.setGlobalAlpha(1);
        gc.setFill(INK);
        gc.fillRect(0, 0, width, height);

        double[] lower = view.ground(0, 0), upper = view.ground(width, height);
        List<Well> wells = new ArrayList<>(bodies.values().stream().filter(b -> b.alpha > .01 && b.depth > 1)
                .map(b -> b.well).toList());
        // La matière noire fait frémir la poussière de l'espace, sans toucher aux orbites
        wells.addAll(darkMatter());
        WellIndex index = new WellIndex(wells, 8_000,
                lower[0] - 14_000, lower[1] - 14_000, upper[0] + 14_000, upper[1] + 14_000);

        place(view);
        Point hovered = mouseInside ? nearest(mouseX, mouseY) : null;
        Point shown = selectedId == null ? hovered : points.getOrDefault(selectedId, hovered);
        String focused = shown == null ? null : shown.owner();
        buildLenses(width, height);

        drawStars(width, height);
        drawDust(view, index, lower, upper, width, height);
        drawCapture();
        space.flush(gc);
        drawHorizons(gc);
        drawLinks(gc, focused, query, width, height);
        drawBodies(gc, query, width, height);
        if (!paused && time >= nextEncounterCheck) {
            updatePackets();
            nextEncounterCheck = time + .1;
        }
        drawPackets();
        matter.flush(gc);
        drawSelection(gc, selectedId, query);

        Field displayed = paused && lastField != null ? lastField : field;
        // Un objet cliqué remplace le cartouche par sa fiche d'observation
        Point observed = selectedId == null || displayed == null ? null : points.get(selectedId);
        panelBottom = observed == null ? 440 : Math.min(height - 70, 700);
        drawLabels(gc, displayed, focused, query, width, height);
        if (observed != null) {
            drawDossier(gc, displayed, observed, width);
        } else {
            drawMonitor(gc, displayed, width, height, camera);
        }
        drawLoad(gc, displayed, width, height);
        if (shown != null && displayed != null && observed == null) drawInspector(gc, displayed, shown, height, width);
        drawHud(gc, width, height, status);
        if (field == null) {
            drawLoading(gc, width, height, text("LECTURE DES PROCESSUS", "READING PROCESSES"),
                    text("PREMIER RELEVÉ DE LA MACHINE", "FIRST MACHINE SAMPLE"), true);
        } else if (displayed != null && !displayed.cpuSampled()) {
            drawLoading(gc, width, height, text("MESURE DU CPU", "MEASURING CPU"),
                    text("LA CHARGE SE MESURE ENTRE DEUX RELEVÉS", "LOAD IS MEASURED BETWEEN TWO SAMPLES"), false);
        }
    }

    /**
     * Le chargement : un anneau de particules dont la tête tourne autour d'un noyau qui palpite,
     * et une barre rouge qui balaie. Au centre tant que rien n'est lu, puis en haut de la carte
     * pendant qu'on attend le second relevé qui donne le CPU. Elle suit l'horloge de la carte :
     * la pause la fige avec le reste.
     */
    private void drawLoading(GraphicsContext gc, double width, double height, String title, String detail,
                             boolean prominent) {
        double clock = time;
        double w = prominent ? 360 : 310, h = 76;
        double x = width / 2 - w / 2, y = prominent ? height * .44 - h / 2 : 64;
        gc.setFill(INK.deriveColor(0, 1, 1, .88));
        gc.fillRect(x, y, w, h);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRect(x + .5, y + .5, w - 1, h - 1);

        double rx = x + 36, ry = y + h / 2;
        for (int k = 0; k < 56; k++) {
            double a = k * TAU / 56;
            double head = Math.pow(.5 + .5 * Math.cos(a - clock * 2.4), 6);
            // La tête se colore comme une charge qui monte
            matter.add(rx + Math.cos(a) * 20, ry + Math.sin(a) * 20, head > .5 ? 2 : 1.2, head, .15 + .85 * head);
        }
        double beat = .5 + .5 * Math.sin(clock * 3);
        for (int k = 0; k < 40; k++) {
            long seed = mix(k * 7_919L + 3);
            double a = unit(seed, 0) * TAU + clock * (.3 + .5 * unit(seed, 10));
            double d = (4 + 3 * beat) * Math.sqrt(unit(seed, 20));
            matter.add(rx + Math.cos(a) * d, ry + Math.sin(a) * d, 1.3, .15 + .5 * beat, .5 + .5 * unit(seed, 30));
        }
        matter.flush(gc);

        double tx = x + 72, tw = w - 88;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(MICRO);
        gc.setFill(ACCENT);
        gc.fillText(text("RELEVÉ EN COURS", "SAMPLING IN PROGRESS"), tx, y + 20);
        gc.setFont(LABEL);
        gc.setFill(TEXT);
        gc.fillText(title, tx, y + 38, tw);
        gc.setFont(CAPTION);
        gc.setFill(MUTED);
        gc.fillText(detail, tx, y + 53, tw);
        double barY = y + h - 12;
        gc.setFill(FAINT);
        gc.fillRect(tx, barY, tw, 2);
        gc.setFill(ACCENT);
        double head = (clock * .45) % 1;
        double start = Math.max(0, head - .25);
        gc.fillRect(tx + tw * start, barY, tw * (head - start), 2);
    }

    // --- Lissage des relevés ---------------------------------------------------------------

    private void update(Field field, double dt) {
        if (field != null && field != lastField && !paused) {
            bodies.values().forEach(b -> b.present = false);
            for (Mass mass : field.masses()) {
                Body body = bodies.computeIfAbsent(mass.key(), ignored -> new Body(mass));
                body.present = true;
                relocate(body, mass);
            }
            lastField = field;
        }
        double ease = 1 - Math.exp(-dt / 1.4);
        // Un changement de place se rattrape en glissant, jamais en fondu : un corps qui
        // devenait transparent à chaque relevé donnait l'impression d'une carte qui charge
        double settle = Math.exp(-dt / .9);
        for (Body body : bodies.values()) {
            double targetAlpha = body.present ? 1 : 0;
            body.alpha += (targetAlpha - body.alpha) * (1 - Math.exp(-dt / .22));
            body.shiftX *= settle;
            body.shiftY *= settle;
            body.satelliteShifts.values().forEach(shift -> { shift[0] *= settle; shift[1] *= settle; });
            body.depth += ((body.present ? body.mass.wellDepth() : 0) - body.depth) * ease;
            body.charge += ((body.present ? activity(body.mass.cpuShare(), cores()) : 0) - body.charge) * ease;
            Mass mass = body.mass;
            body.well = SpacetimeLayout.wellOf(mass.homeX() + body.shiftX, mass.homeY() + body.shiftY,
                    mass.bodyRadius(), mass.systemRadius(), body.depth * body.alpha);
            Map<Integer, Double> rings = new HashMap<>();
            for (Anomaly a : mass.anomalies()) {
                rings.put(a.ring(), a.orbitRadius());
                body.appearances.merge(processId(a.process()), Math.min(1, dt / .8),
                        (a0, a1) -> Math.min(1, a0 + a1));
            }
            // La vitesse est intégrée : un changement de charge ne peut pas faire sauter les phases
            rings.forEach((ring, radius) -> body.phases.merge(ring,
                    SpacetimeLayout.angularSpeed(radius, body.depth) * dt, (a, b) -> (a + b) % TAU));
            body.appearances.keySet().removeIf(id -> mass.anomalies().stream()
                    .noneMatch(a -> processId(a.process()).equals(id)));
        }
        bodies.values().removeIf(b -> !b.present && b.alpha < .01);
    }

    /**
     * Adopte le nouveau relevé tout de suite, mais retient d'où chaque corps partait : l'écart
     * est ajouté à l'affichage puis fond en une seconde.
     */
    private static void relocate(Body body, Mass next) {
        Mass old = body.mass;
        if (old == next) return;
        body.shiftX += old.homeX() - next.homeX();
        body.shiftY += old.homeY() - next.homeY();
        Map<String, double[]> before = new HashMap<>();
        for (Anomaly a : old.anomalies()) before.put(processId(a.process()), satelliteRest(body, old, a));
        Map<String, double[]> shifts = new HashMap<>();
        for (Anomaly a : next.anomalies()) {
            String id = processId(a.process());
            double[] from = before.get(id);
            double[] shift = body.satelliteShifts.getOrDefault(id, new double[2]);
            if (from != null) {
                double[] to = satelliteRest(body, next, a);
                // L'écart du corps est déjà porté par shiftX/shiftY : on ne garde que le mouvement propre
                shift[0] += from[0] - to[0] - (old.homeX() - next.homeX());
                shift[1] += from[1] - to[1] - (old.homeY() - next.homeY());
            }
            shifts.put(id, shift);
        }
        body.satelliteShifts.clear();
        body.satelliteShifts.putAll(shifts);
        body.mass = next;
    }

    private static double[] satelliteRest(Body body, Mass mass, Anomaly a) {
        double angle = a.slotAngle() + body.phases.getOrDefault(a.ring(), 0.0);
        return new double[] { mass.homeX() + Math.cos(angle) * a.orbitRadius(),
                mass.homeY() + Math.sin(angle) * a.orbitRadius() };
    }

    /** Les corps restent dans le plan de lecture ; les satellites battent doucement sur leur orbite. */
    private void place(Perspective view) {
        points.clear();
        int cores = cores();
        for (Body body : bodies.values()) {
            Mass mass = body.mass;
            double homeX = mass.homeX() + body.shiftX, homeY = mass.homeY() + body.shiftY;
            view.project(homeX, homeY, 0, projected);
            points.put("mass:" + mass.key(), new Point("mass:" + mass.key(), mass.key(), mass.principal(),
                    projected[0], projected[1], mass.bodyRadius() * view.zoom(),
                    homeX, homeY, body.alpha, -1, body.charge, mass.cpuShare() * cores * 100));
            for (Anomaly a : mass.anomalies()) {
                double[] rest = satelliteRest(body, mass, a);
                double[] shift = body.satelliteShifts.get(processId(a.process()));
                double x = rest[0] + body.shiftX + (shift == null ? 0 : shift[0]);
                double y = rest[1] + body.shiftY + (shift == null ? 0 : shift[1]);
                // L'amplitude reste sous la moitié de l'écart libre entre deux anneaux :
                // aucun satellite ne peut toucher son voisin
                double charge = activity(a.cpuShare(), cores);
                double dx = x - homeX, dy = y - homeY, distance = Math.max(1, Math.hypot(dx, dy));
                double beat = Math.sin(time * (.35 + charge * 2.2) + a.slotAngle() * 7)
                        * Math.min(a.radius() * .3, 250);
                x += dx / distance * beat;
                y += dy / distance * beat;
                SpacetimeLayout.surface(x, y, List.of(body.well), surface);
                view.project(surface[0], surface[1], 0, projected);
                String id = processId(a.process());
                points.put(id, new Point(id, mass.key(), a.process(), projected[0], projected[1],
                        a.radius() * view.zoom(), surface[0], surface[1],
                        body.alpha * body.appearances.getOrDefault(id, 0.0), a.ring(), charge,
                        a.cpuShare() * cores * 100));
            }
        }
    }

    // --- L'horizon et la lentille -------------------------------------------------------------

    /**
     * Le rayon de l'horizon à l'écran. Il est fixé dans le monde et suit donc le zoom, comme les
     * corps. Mesuré sur une capture : calculé en pixels, il gardait sa taille en vue d'ensemble
     * pendant que la carte rétrécissait, et les horizons se recouvraient en cercles géants.
     */
    private double horizonOf(Point point) {
        return SpacetimeLayout.horizonRadius(point.radius() / zoom, point.activity()) * zoom;
    }

    private void buildLenses(double width, double height) {
        lenses.clear();
        for (Point point : points.values()) {
            if (point.ring() >= 0 || point.alpha() < .1) continue;
            double horizon = horizonOf(point) * point.alpha();
            // Un horizon de moins de deux pixels ne se verrait pas : inutile de courber la lumière
            if (horizon < 2 || !onScreen(point.x(), point.y(), horizon * 4, width, height)) continue;
            lenses.add(new Lens(point.owner(), point.x(), point.y(), horizon, point.activity()));
        }
    }

    /**
     * Où la lumière d'un point apparaît, une fois courbée par les horizons voisins. C'est la
     * formule d'une lentille gravitationnelle : r' = (r + √(r² + 4·θ²)) / 2. Ce qui est derrière
     * l'horizon est repoussé sur son bord, en anneau, comme dans un miroir. L'effet s'éteint en
     * douceur à quelques rayons de distance.
     *
     * @return l'influence la plus forte subie, entre 0 et 1
     */
    private double bend(double x, double y) {
        double bx = x, by = y, strongest = 0;
        for (Lens lens : lenses) {
            double dx = bx - lens.x(), dy = by - lens.y();
            double einstein = lens.horizon() * 1.12;
            // La déformation reste dans le territoire de la famille : elle ne tord pas les voisines
            double reach = einstein * 2.6;
            if (Math.abs(dx) > reach || Math.abs(dy) > reach) continue;
            double d = Math.hypot(dx, dy);
            if (d >= reach) continue;
            double weight = smooth(reach, einstein * 1.4, d);
            double image = (d + Math.sqrt(d * d + 4 * einstein * einstein)) / 2;
            double moved = d + (image - d) * weight;
            if (d < 1e-6) {
                dx = 1;
                d = 1;
            }
            bx = lens.x() + dx / d * moved;
            by = lens.y() + dy / d * moved;
            strongest = Math.max(strongest, weight);
        }
        bent[0] = bx;
        bent[1] = by;
        return strongest;
    }

    /**
     * Le trou : un noir profond qui se fond dans l'espace sur son bord. Aucun cercle ne le
     * délimite : c'est la poussière repoussée par la lentille qui en dessine le contour.
     */
    private void drawHorizons(GraphicsContext gc) {
        for (Lens lens : lenses) {
            double outer = lens.horizon() * 1.35;
            gc.setFill(new RadialGradient(0, 0, lens.x(), lens.y(), outer, false, CycleMethod.NO_CYCLE,
                    new Stop(0, INK), new Stop(.72, INK.deriveColor(0, 1, 1, .97)),
                    new Stop(1, INK.deriveColor(0, 1, 1, 0))));
            gc.fillOval(lens.x() - outer, lens.y() - outer, outer * 2, outer * 2);
        }
    }

    // --- L'espace ---------------------------------------------------------------------------

    private void drawStars(double width, double height) {
        for (int i = 0; i + 2 < stars.length; i += 3) {
            double twinkle = .1 + .22 * (.5 + .5 * Math.sin(time * (.3 + stars[i + 2]) + i));
            bend(stars[i] * width, stars[i + 1] * height);
            space.add(bent[0], bent[1], stars[i + 2] > .92 ? 1.6 : 1, -1, twinkle);
        }
    }

    /**
     * La poussière de l'espace : des grains posés sur un réseau lâche, qui dérivent au hasard et
     * tremblent sans cesse. Les puits les attirent, et les horizons courbent leur lumière.
     */
    private void drawDust(Perspective view, WellIndex index, double[] low, double[] high, double width, double height) {
        double level = Math.log(DUST_PX / (SpacetimeLayout.GRID_STEP * view.zoom())) / Math.log(2);
        double spacing = SpacetimeLayout.GRID_STEP * Math.pow(2, Math.ceil(level));
        double fine = Math.ceil(level) - level;
        int budget = dustLayer(view, index, low, high, width, height, spacing, 0, 1, MAX_DUST);
        if (fine > .04) dustLayer(view, index, low, high, width, height, spacing, .5, fine, budget);
    }

    private int dustLayer(Perspective view, WellIndex index, double[] low, double[] high, double width, double height,
                          double spacing, double offset, double opacity, int budget) {
        double margin = spacing * 3;
        long firstX = (long) Math.floor((low[0] - margin) / spacing), lastX = (long) Math.ceil((high[0] + margin) / spacing);
        long firstY = (long) Math.floor((low[1] - margin) / spacing), lastY = (long) Math.ceil((high[1] + margin) / spacing);
        for (long i = firstX; i <= lastX && budget > 0; i++) {
            for (long j = firstY; j <= lastY && budget > 0; j++) {
                long seed = mix(i * 91_381L + j * 7_919L + (offset > 0 ? 17 : 0));
                // Une dérive lente et désordonnée : chaque grain erre autour de sa place
                double driftX = (noise(unit(seed, 0) * 50, time * (.05 + .08 * unit(seed, 10))) - .5) * 1.6;
                double driftY = (noise(unit(seed, 20) * 50 + 17, time * (.05 + .08 * unit(seed, 30))) - .5) * 1.6;
                double x = (i + offset + (unit(seed, 40) - .5) * .6 + driftX) * spacing;
                double y = (j + offset + (unit(seed, 50) - .5) * .6 + driftY) * spacing;
                List<Well> near = index.near(x, y);
                double curvature = 0;
                if (near.isEmpty()) {
                    view.project(x, y, 0, projected);
                } else {
                    SpacetimeLayout.surface(x, y, near, SHEET_PASSES, surface);
                    view.project(surface[0], surface[1], surface[2], projected);
                    curvature = Math.min(1, Math.hypot(surface[0] - x, surface[1] - y) / spacing * .4
                            + Math.max(0, -surface[2]) / (SpacetimeLayout.MAX_WELL_DEPTH * .6));
                }
                if (projected[0] < -20 || projected[0] > width + 20 || projected[1] < -20 || projected[1] > height + 20) continue;
                double lensed = bend(projected[0], projected[1]);
                double twinkle = .6 + .4 * Math.sin(time * (.8 + 2 * unit(seed, 60)) + unit(seed, 0) * TAU);
                // Une nébuleuse, fixée dans le monde : des régions denses et des vides, pour que
                // l'espace ne soit pas un semis uniforme
                double nebula = noise(x / 42_000 + 3.1, y / 42_000 - 1.7) * .7 + noise(x / 13_000, y / 13_000 + 9.2) * .3;
                double density = smooth(.28, .78, nebula);
                if (lensed < .1 && unit(seed, 70) > .25 + .75 * density) continue;
                double alpha = (.18 + .45 * curvature + .55 * lensed + .22 * density) * twinkle * opacity;
                space.add(bent[0], bent[1], lensed > .5 || curvature > .4 ? 1.5 : 1.1,
                        lensed > .15 ? .15 + .3 * lensed : -1, alpha);
                budget--;
            }
        }
        return budget;
    }

    /** Autour de chaque famille active, un tourbillon aspire la poussière dans l'horizon. */
    private void drawCapture() {
        for (Lens lens : lenses) {
            vortex(space, lens.owner(), lens.x(), lens.y(), lens.horizon(), lens.charge());
        }
    }

    /**
     * Le tourbillon : un nuage de particules enroulé en bras de spirale, qui tourne et glisse
     * vers l'horizon.
     *
     * Chaque particule avance de s = 0 (bord) à s = 1 (horizon). Son rayon fond en
     * r = H + (R − H)(1 − s)^1,4, et son angle suit la spirale logarithmique de son bras :
     * θ = bras + torsion · ln(R / r) + rotation · t. L'angle ne dépend que du rayon et du temps,
     * de façon continue : une particule s'enroule de plus en plus serré en approchant, sans
     * jamais tourner brusquement. Mesuré sur la version précédente : un angle en spin · p²,
     * tiré au hasard à chaque chute, faisait virer les grains dans tous les sens.
     *
     * Le nuage n'est pas homogène : il s'amincit près de l'horizon, et un bruit y forme des
     * amas le long des bras. Nombre de bras, vitesse, torsion et éclat suivent la charge.
     */
    private void vortex(Particles batch, String key, double x, double y, double horizon, double charge) {
        long base = key.hashCode();
        int arms = 2 + (int) (base & 1) + (charge > .5 ? 1 : 0);
        // Un grand horizon à l'écran reçoit plus de particules : sinon son tourbillon, étalé sur
        // une vaste surface, se diluait jusqu'à ne plus former de spirale lisible
        double reachFactor = Math.max(1, Math.min(3, horizon / 50));
        int grains = (int) Math.min(1_500, (70 + 490 * charge) * reachFactor);
        double outer = horizon * (2.3 + .7 * charge);
        double direction = (base >>> 3 & 1) == 0 ? 1 : -1;
        double rotation = time * (.12 + .7 * charge) * direction;
        double twist = (2 + 1.6 * charge) * direction;
        double armWidth = TAU / arms * (.34 - .14 * charge);
        for (int k = 0; k < grains; k++) {
            long seed = mix(base * 977L + k);
            double speed = (.04 + .3 * charge) * (.7 + .6 * unit(seed, 0));
            double s = (time * speed + unit(seed, 10)) % 1;
            double r = horizon + (outer - horizon) * Math.pow(1 - s, 1.4);
            int arm = (int) (unit(seed, 20) * arms) % arms;
            // Le nuage se resserre sur son bras en approchant de l'horizon
            double spread = (unit(seed, 30) - .5) * armWidth * (.35 + .65 * (1 - s));
            double theta = arm * TAU / arms + spread + twist * Math.log(outer / r) + rotation;
            double clump = noise(Math.log(r / horizon) * 3 + arm * 7.3, time * .15 + unit(seed, 40) * 2);
            double alpha = Math.pow(Math.sin(Math.PI * s), .55) * (.25 + .6 * charge) * (.3 + .7 * clump) * (.55 + .45 * s);
            double size = 1 + s * 1.1 + (clump > .68 ? .5 : 0);
            batch.add(x + Math.cos(theta) * r, y + Math.sin(theta) * r, size, charge * (.35 + .65 * s), alpha);
        }
    }

    /** La matière noire : des amas invisibles qui creusent légèrement l'espace et dérivent lentement. */
    private List<Well> darkMatter() {
        List<Well> clumps = new ArrayList<>();
        double extent = 0;
        for (Body body : bodies.values()) {
            if (body.alpha < .05) continue;
            Mass mass = body.mass;
            extent = Math.max(extent, Math.hypot(mass.homeX(), mass.homeY()) + SpacetimeLayout.territory(mass));
            double density = Math.min(1, Math.sqrt(mass.memoryShare()) * 4);
            int count = (int) Math.round(density * 6);
            double territory = SpacetimeLayout.territory(mass);
            for (int c = 0; c < count; c++) {
                long seed = mix(mass.key().hashCode() * 31L + c * 7919L);
                double orbit = territory * (.45 + 1.1 * unit(seed, 0)) * (1 + .12 * Math.sin(time * .21 + unit(seed, 10) * TAU));
                double speed = (.012 + .03 * unit(seed, 20)) * ((seed & 1) == 0 ? 1 : -1);
                double angle = unit(seed, 30) * TAU + time * speed;
                double core = 1_400 + 2_600 * unit(seed, 40);
                double depth = (500 + 1_800 * unit(seed, 50)) * (.35 + .65 * density) * body.alpha;
                clumps.add(new Well(mass.homeX() + Math.cos(angle) * orbit, mass.homeY() + Math.sin(angle) * orbit,
                        depth * 1.6, core, core * 3.2, 0));
            }
        }
        for (int c = 0; c < 24 && extent > 0; c++) {
            long seed = mix(9_176_451L + c * 104_729L);
            double x = (unit(seed, 0) * 2 - 1) * extent + Math.sin(time * .017 + unit(seed, 10) * TAU) * 9_000;
            double y = (unit(seed, 20) * 2 - 1) * extent + Math.cos(time * .013 + unit(seed, 30) * TAU) * 9_000;
            double core = 2_000 + 3_000 * unit(seed, 40);
            clumps.add(new Well(x, y, 700 + 1_400 * unit(seed, 50), core, core * 3, 0));
        }
        return clumps;
    }

    // --- Les corps ---------------------------------------------------------------------------

    private void drawBodies(GraphicsContext gc, String query, double width, double height) {
        boolean searching = query != null && !query.isBlank();
        for (Point point : points.values()) {
            double r = point.radius();
            if (!onScreen(point.x(), point.y(), r * 2 + 12, width, height)) continue;
            Body body = bodies.get(point.owner());
            double alpha = point.alpha();
            if (searching && !matches(body.mass, query)) alpha *= .22;
            if (alpha < .02) continue;
            if (point.ring() < 0) {
                core(gc, point, alpha);
            } else {
                satellite(gc, point, alpha);
            }
        }
    }

    /**
     * Une famille : une sphère pleine de particules qui tournent, un réseau de traits très fins sur
     * sa surface, des piques et des éclats autour. La charge change sa couleur et sa vitesse.
     */
    private void core(GraphicsContext gc, Point point, double alpha) {
        double cx = point.x(), cy = point.y(), r = Math.max(2.5, point.radius()), charge = point.activity();
        long seed = point.id().hashCode();
        double yaw = time * (.12 + 1.4 * charge) + (seed & 0xFF) * .03;
        double tilt = .45 + (seed >>> 8 & 0xFF) / 255.0 * .5;

        // Le volume : des particules sur des coquilles, plus nombreuses près de la surface
        int count = bucket((int) Math.max(48, Math.min(1_400, r * r * .9)));
        double[][] sphere = spherePoints(count);
        for (int i = 0; i < count; i++) {
            long h = mix(seed * 31L + i);
            double shell = .3 + .7 * Math.sqrt(unit(h, 0));
            shell *= 1 + .05 * Math.sin(time * (1 + charge * 5) + i);
            rotate(sphere[i], yaw, tilt);
            double front = (turned[2] + 1) / 2;
            double particleAlpha = alpha * (.18 + .7 * front) * (.4 + .6 * shell);
            matter.add(cx + turned[0] * r * shell, cy + turned[1] * r * shell,
                    shell > .9 && front > .6 ? 1.6 : 1.1, charge + (unit(h, 10) - .5) * .1, particleAlpha);
        }

        // Le réseau de traits très fins, entre voisins de la surface
        if (r >= 12) {
            int nodes = bucket((int) Math.max(48, Math.min(384, r * 2.2)));
            double[][] surfacePoints = spherePoints(nodes);
            int[] pairs = plexus(nodes);
            gc.setLineWidth(.5);
            gc.setStroke(stage(charge).deriveColor(0, 1, 1, alpha * .32));
            double[] px = new double[nodes], py = new double[nodes], pz = new double[nodes];
            for (int i = 0; i < nodes; i++) {
                rotate(surfacePoints[i], yaw * .7, tilt);
                px[i] = cx + turned[0] * r;
                py[i] = cy + turned[1] * r;
                pz[i] = turned[2];
            }
            gc.beginPath();
            for (int e = 0; e < pairs.length; e += 2) {
                int a = pairs[e], b = pairs[e + 1];
                if (pz[a] + pz[b] < -.2) continue;
                gc.moveTo(px[a], py[a]);
                gc.lineTo(px[b], py[b]);
            }
            gc.stroke();
            for (int i = 0; i < nodes; i++) {
                if (pz[i] > .2) matter.add(px[i], py[i], 1.8, charge, alpha * .9);
            }
        }

        // Les piques : de fins traits qui partent du bord, plus longs et plus agités sous la charge
        int spikes = (int) Math.max(10, Math.min(90, r * 1.1));
        gc.setLineWidth(.6);
        gc.setStroke(stage(charge).deriveColor(0, 1, 1, alpha * .45));
        gc.beginPath();
        for (int s = 0; s < spikes; s++) {
            long h = mix(seed * 17L + s);
            double a = unit(h, 0) * TAU + time * (.05 + .3 * charge);
            double flicker = .5 + .5 * Math.sin(time * (1.5 + 6 * charge) + unit(h, 10) * TAU);
            double length = r * (.08 + (.25 + .6 * charge) * unit(h, 20)) * flicker + 1.5;
            double inner = r * 1.02, outer = inner + length;
            gc.moveTo(cx + Math.cos(a) * inner, cy + Math.sin(a) * inner);
            gc.lineTo(cx + Math.cos(a) * outer, cy + Math.sin(a) * outer);
            matter.add(cx + Math.cos(a) * (outer + 2), cy + Math.sin(a) * (outer + 2), 1.2, charge, alpha * flicker * .8);
        }
        gc.stroke();

        // Les éclats : des particules arrachées qui s'éloignent et s'éteignent
        int sparks = (int) (8 + 60 * charge + Math.min(40, r * .4));
        for (int s = 0; s < sparks; s++) {
            long h = mix(seed * 53L + s);
            double clock = time * (.15 + .9 * charge) * (.6 + .8 * unit(h, 0)) + unit(h, 10);
            long cycle = (long) Math.floor(clock);
            double p = clock - cycle;
            long birth = mix(h + cycle);
            double a = unit(birth, 0) * TAU;
            double distance = r * (1.05 + p * (.5 + 1.2 * charge));
            matter.add(cx + Math.cos(a) * distance, cy + Math.sin(a) * distance, 1.1,
                    charge + (unit(birth, 10) - .5) * .15, alpha * (1 - p) * .8);
        }
    }

    /**
     * Un sous-processus : une petite sphère dont la forme dit le CPU, en pourcentage d'un cœur.
     *   — moins de 5 %      : un nuage de points ;
     *   — de 5 à 50 %       : une sphère géodésique, entourée d'arcs ;
     *   — de 50 à 500 %     : une cage de polygones, qui projette des éclats ;
     *   — plus de 500 %     : un globe dense et plein.
     */
    private void satellite(GraphicsContext gc, Point point, double alpha) {
        double cx = point.x(), cy = point.y(), r = point.radius(), charge = point.activity();
        int type = satelliteType(point.corePercent());
        Color colour = stage(charge);
        if (r < 3) {
            matter.add(cx, cy, 2, charge, alpha);
            matter.add(cx + 2, cy - 1, 1, charge, alpha * .5);
            matter.add(cx - 1.5, cy + 1.5, 1, charge, alpha * .4);
            return;
        }
        long seed = point.id().hashCode();
        double yaw = time * (.2 + 1.6 * charge) + (seed & 0xFF) * .05;
        double tilt = .35 + (seed >>> 8 & 0xFF) / 255.0 * .6;
        // Trop petite pour lire des arêtes : une sphère de points, quelle que soit sa forme
        if (type == 0 || r < 7) {
            int count = bucket((int) Math.max(48, Math.min(384, r * r * 1.4)));
            double[][] sphere = spherePoints(count);
            for (int i = 0; i < count; i++) {
                rotate(sphere[i], yaw, tilt);
                double front = (turned[2] + 1) / 2;
                matter.add(cx + turned[0] * r, cy + turned[1] * r, front > .7 ? 1.3 : 1, charge, alpha * (.2 + .75 * front));
            }
            return;
        }
        switch (type) {
            case 1 -> {
                mesh(gc, GEODESIC, cx, cy, r, yaw, tilt, colour, alpha * .75, alpha * .15, .6, true, charge, alpha);
                gc.setStroke(colour.deriveColor(0, 1, 1, alpha * .5));
                gc.setLineWidth(.7);
                double ring = r * 1.25, start = Math.toDegrees(yaw) % 360;
                gc.strokeArc(cx - ring, cy - ring, ring * 2, ring * 2, start, 70, ArcType.OPEN);
                gc.strokeArc(cx - ring, cy - ring, ring * 2, ring * 2, start + 180, 45, ArcType.OPEN);
            }
            case 2 -> {
                mesh(gc, GEODESIC, cx, cy, r, yaw, tilt, colour, alpha * .22, alpha * .06, .4, false, charge, alpha);
                mesh(gc, CAGE, cx, cy, r * 1.02, yaw, tilt, colour, alpha * .9, alpha * .2, 1.1, true, charge, alpha);
                gc.setStroke(colour.deriveColor(0, 1, 1, alpha * .5));
                gc.setLineWidth(.6);
                for (int s = 0; s < 6; s++) {
                    rotate(CAGE.vertices()[s * 9 % CAGE.vertices().length], yaw, tilt);
                    if (Math.abs(turned[2]) > .45) continue;
                    double flicker = .5 + .5 * Math.sin(time * (2 + 4 * charge) + s);
                    gc.strokeLine(cx + turned[0] * r * 1.05, cy + turned[1] * r * 1.05,
                            cx + turned[0] * r * (1.3 + .4 * flicker), cy + turned[1] * r * (1.3 + .4 * flicker));
                }
            }
            default -> {
                gc.setFill(new RadialGradient(0, 0, cx - r * .3, cy - r * .35, r * 1.3, false, CycleMethod.NO_CYCLE,
                        new Stop(0, colour.deriveColor(0, .7, .45, alpha * .9)), new Stop(1, INK.deriveColor(0, 1, 1, alpha * .9))));
                gc.fillOval(cx - r, cy - r, r * 2, r * 2);
                mesh(gc, r >= 16 ? GEODESIC_FINE : GEODESIC, cx, cy, r, yaw, tilt, colour, alpha * .6, alpha * .12, .5, true, charge, alpha);
                gc.setStroke(colour.deriveColor(0, 1, 1, alpha * .8));
                gc.setLineWidth(1);
                gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
            }
        }
    }

    /** Dessine les arêtes d'une forme tournée, celles de derrière plus pâles que celles de devant. */
    private void mesh(GraphicsContext gc, Mesh mesh, double cx, double cy, double r, double yaw, double tilt,
                      Color colour, double frontAlpha, double backAlpha, double lineWidth, boolean dots,
                      double charge, double alpha) {
        double[][] vertices = mesh.vertices();
        int n = Math.min(vertices.length, meshX.length);
        for (int i = 0; i < n; i++) {
            rotate(vertices[i], yaw, tilt);
            meshX[i] = cx + turned[0] * r;
            meshY[i] = cy + turned[1] * r;
            meshZ[i] = turned[2];
        }
        int[] edges = mesh.edges();
        gc.setLineWidth(lineWidth);
        for (int pass = 0; pass < 2; pass++) {
            boolean front = pass == 1;
            gc.setStroke(colour.deriveColor(0, 1, 1, front ? frontAlpha : backAlpha));
            gc.beginPath();
            for (int e = 0; e < edges.length; e += 2) {
                int a = edges[e], b = edges[e + 1];
                if (a >= n || b >= n || (meshZ[a] + meshZ[b] >= 0) != front) continue;
                gc.moveTo(meshX[a], meshY[a]);
                gc.lineTo(meshX[b], meshY[b]);
            }
            gc.stroke();
        }
        if (dots) {
            for (int i = 0; i < n; i++) {
                if (meshZ[i] > 0) matter.add(meshX[i], meshY[i], 1.6, charge, alpha * (.4 + .6 * meshZ[i]));
            }
        }
    }

    private static int satelliteType(double corePercent) {
        return corePercent < 5 ? 0 : corePercent < 50 ? 1 : corePercent < 500 ? 2 : 3;
    }

    /** Tourne un point de la sphère unité autour de l'axe vertical, puis l'incline. Écrit dans turned. */
    private void rotate(double[] p, double yaw, double tilt) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double x = p[0] * cos + p[2] * sin;
        double z = -p[0] * sin + p[2] * cos;
        double ct = Math.cos(tilt), st = Math.sin(tilt);
        turned[0] = x;
        turned[1] = p[1] * ct - z * st;
        turned[2] = p[1] * st + z * ct;
    }

    /** La sélection et la recherche : quatre équerres fines autour de la sphère. */
    private void drawSelection(GraphicsContext gc, String selected, String query) {
        for (Point point : points.values()) {
            Body body = bodies.get(point.owner());
            boolean chosen = point.id().equals(selected);
            boolean found = point.ring() < 0 && matches(body.mass, query);
            if (!chosen && !found) continue;
            gc.setStroke(chosen ? TEXT : ACCENT);
            gc.setLineWidth(1);
            double r = Math.max(6, point.radius() * 1.3) + 6;
            for (int sx : new int[] {-1, 1}) for (int sy : new int[] {-1, 1}) {
                double x = point.x() + sx * r, y = point.y() + sy * r;
                gc.strokeLine(x, y, x - sx * 7, y);
                gc.strokeLine(x, y, x, y - sy * 7);
            }
        }
    }

    // --- Les liens -----------------------------------------------------------------------------

    /**
     * Chaque satellite peut se lier à ses deux plus proches voisins, principal compris, par un trait
     * fin. Chaque lien s'allume et s'éteint à son rythme, plus vite si les processus travaillent,
     * et un lien bien allumé transporte un signal.
     */
    private void drawLinks(GraphicsContext gc, String focused, String query, double width, double height) {
        double fade = 1 - Math.exp(-frameDt / .7);
        gc.setLineWidth(.6);
        for (Body body : bodies.values()) {
            Mass mass = body.mass;
            Point centre = points.get("mass:" + mass.key());
            List<Anomaly> children = mass.anomalies();
            if (children.isEmpty() || !onScreen(centre.x(), centre.y(),
                    mass.systemRadius() * centre.radius() / mass.bodyRadius(), width, height)) {
                body.links.clear();
                continue;
            }
            boolean highlighted = mass.key().equals(focused) || matches(mass, query);
            List<Point> nodes = new ArrayList<>();
            nodes.add(centre);
            for (Anomaly a : children) nodes.add(points.get(processId(a.process())));
            Map<String, Double> wanted = new HashMap<>();
            for (int i = 1; i < nodes.size(); i++) {
                Point p = nodes.get(i);
                int first = -1, second = -1;
                double best = Double.POSITIVE_INFINITY, next = Double.POSITIVE_INFINITY;
                for (int j = 0; j < nodes.size(); j++) {
                    if (j == i) continue;
                    Point q = nodes.get(j);
                    double d = Math.hypot(p.worldX() - q.worldX(), p.worldY() - q.worldY());
                    if (d < best) { next = best; second = first; best = d; first = j; }
                    else if (d < next) { next = d; second = j; }
                }
                for (int j : new int[] {first, second}) {
                    if (j < 0) continue;
                    Point q = nodes.get(j);
                    String key = p.id().compareTo(q.id()) < 0 ? p.id() + ">" + q.id() : q.id() + ">" + p.id();
                    double charge = Math.max(p.activity(), q.activity());
                    double phase = Math.floorMod(key.hashCode(), 6283) / 1000.0;
                    double gate = smooth(.35, .75, .5 + .5 * Math.sin(time * (.16 + charge * 1.6) + phase));
                    wanted.merge(key, gate, Math::max);
                }
            }
            for (String key : wanted.keySet()) body.links.putIfAbsent(key, 0.0);
            body.links.replaceAll((key, alpha) -> alpha + (wanted.getOrDefault(key, 0.0) - alpha) * fade);
            body.links.values().removeIf(alpha -> alpha < .01);

            for (Map.Entry<String, Double> link : body.links.entrySet()) {
                String[] ends = link.getKey().split(">");
                Point from = points.get(ends[0]), to = points.get(ends[1]);
                if (from == null || to == null) continue;
                double alpha = link.getValue() * Math.min(from.alpha(), to.alpha()) * (highlighted ? .75 : .45);
                if (alpha < .02) continue;
                double dx = to.x() - from.x(), dy = to.y() - from.y(), length = Math.hypot(dx, dy);
                if (length <= from.radius() + to.radius() + 6) continue;
                double ux = dx / length, uy = dy / length;
                double x0 = from.x() + ux * (from.radius() + 3), y0 = from.y() + uy * (from.radius() + 3);
                double x1 = to.x() - ux * (to.radius() + 3), y1 = to.y() - uy * (to.radius() + 3);
                double charge = Math.max(from.activity(), to.activity());
                gc.setStroke(stage(charge).deriveColor(0, 1, 1, alpha));
                gc.strokeLine(x0, y0, x1, y1);
                if (link.getValue() > .55) {
                    int hash = link.getKey().hashCode();
                    double u = (time * (.25 + charge * 1.5) + Math.floorMod(hash, 1000) / 1000.0) % 1;
                    double t = (hash & 1) == 0 ? u : 1 - u;
                    double pulse = Math.sin(Math.PI * u);
                    matter.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 2.2, Math.max(.4, charge), Math.min(1, alpha * 2.4) * pulse);
                }
            }
        }
    }

    // --- Les paquets -------------------------------------------------------------------------

    /** Deux satellites d'anneaux voisins qui passent au plus près s'échangent un paquet. */
    private void updatePackets() {
        packets.removeIf(p -> time - p.start() > 1.8 || !points.containsKey(p.from()) || !points.containsKey(p.to()));
        Map<String, Boolean> closeNow = new HashMap<>();
        for (Body body : bodies.values()) {
            if (body.alpha < .9 || body.depth < 50) continue;
            List<Anomaly> children = body.mass.anomalies();
            for (int i = 0; i < children.size(); i++) {
                Point a = points.get(processId(children.get(i).process()));
                if (a.radius() < 1.4) continue;
                for (int j = i + 1; j < children.size(); j++) {
                    Point b = points.get(processId(children.get(j).process()));
                    if (Math.abs(a.ring() - b.ring()) != 1 || b.radius() < 1.4) continue;
                    double gap = Math.abs(children.get(i).orbitRadius() - children.get(j).orbitRadius());
                    double distance = Math.hypot(a.worldX() - b.worldX(), a.worldY() - b.worldY());
                    boolean close = distance < gap * 1.08;
                    String key = a.id() + "/" + b.id();
                    closeNow.put(key, close);
                    if (close && Boolean.FALSE.equals(encounters.get(key)) && packets.size() < MAX_PACKETS) {
                        packets.add(new Packet(a.id(), b.id(), time));
                    }
                }
            }
        }
        encounters.clear();
        encounters.putAll(closeNow);
    }

    /** Un paquet est une courte traînée de particules qui saute d'un satellite à l'autre. */
    private void drawPackets() {
        for (Packet packet : packets) {
            Point from = points.get(packet.from()), to = points.get(packet.to());
            if (from == null || to == null) continue;
            double t = (time - packet.start()) / 1.8;
            if (t < 0 || t > 1) continue;
            for (int trail = 5; trail >= 0; trail--) {
                double u = t - trail * .03;
                if (u < 0) continue;
                double x = from.x() + (to.x() - from.x()) * u;
                double y = from.y() + (to.y() - from.y()) * u - Math.sin(Math.PI * u) * 10;
                matter.add(x, y, trail == 0 ? 2.4 : 1.4, trail == 0 ? .66 : .1 * trail,
                        Math.sin(Math.PI * t) * (1 - trail * .15));
            }
        }
    }

    // --- Les textes ----------------------------------------------------------------------------

    /**
     * Les noms sont des légendes : un petit anneau, un trait fin qui part du corps en oblique,
     * le nom en capitales et la mesure dessous. Les plus lourdes familles sont toujours légendées.
     */
    private void drawLabels(GraphicsContext gc, Field field, String focused, String query, double width, double height) {
        labels.clear();
        labels.add(new Box(0, 0, 420, 150));
        labels.add(new Box(width - 330, 40, 330, panelBottom - 40));
        if (field == null) return;
        List<Body> ordered = new ArrayList<>(bodies.values());
        ordered.sort(Comparator.comparing((Body b) -> !b.mass.key().equals(focused))
                .thenComparing(Comparator.comparingLong((Body b) -> b.mass.memoryBytes()).reversed()));
        int count = 0;
        for (Body body : ordered) {
            Mass mass = body.mass;
            Point p = points.get("mass:" + mass.key());
            boolean wanted = matches(mass, query) || mass.key().equals(focused);
            if (!body.present || p.radius() < 4 && !wanted) continue;
            if (count >= 10 && !wanted) continue;
            // La légende part de l'horizon s'il y en a un : elle ne traverse pas le trou noir
            double r = Math.max(Math.max(4, p.radius() * 1.3), horizonOf(p));
            double startX = p.x() + r * .72 + 2, startY = p.y() - r * .72 - 2;
            double elbowX = startX + 16, elbowY = startY - 16;
            double endX = elbowX + 34;
            String name = mass.name().toUpperCase(Locale.ROOT) + (mass.processCount() > 1 ? "  ×" + mass.processCount() : "");
            String detail = (mass.memoryKnown() ? memory(mass.memoryBytes()) : text("MÉMOIRE INCONNUE", "MEMORY UNKNOWN")) + "   ·   CPU "
                    + (field.cpuSampled() ? percent(mass.cpuShare()) : "…");
            double textX = endX + 9;
            double w = Math.max(name.length() * 7.4, detail.length() * 5.6) + 12;
            Box box = new Box(startX - 2, elbowY - 16, textX - startX + w, 38);
            if (box.x() < 12 || box.x() + box.width() > width - 12 || box.y() < 12 || elbowY > height - 90
                    || labels.stream().anyMatch(box::hits)) continue;
            labels.add(box);
            count++;
            gc.setGlobalAlpha(body.alpha);
            gc.setStroke(MUTED.deriveColor(0, 1, 1, .7));
            gc.setLineWidth(.8);
            gc.strokeLine(startX, startY, elbowX, elbowY);
            gc.strokeLine(elbowX, elbowY, endX, elbowY);
            gc.setStroke(TEXT);
            gc.strokeOval(endX - 2.5, elbowY - 2.5, 5, 5);
            gc.setFill(INK.deriveColor(0, 1, 1, .6));
            gc.fillRect(textX - 4, elbowY - 14, w, 32);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(TEXT);
            gc.setFont(LABEL);
            gc.fillText(name, textX, elbowY + 1);
            gc.setFont(CAPTION);
            gc.setFill(field.cpuSampled() && p.activity() > HORIZON_THRESHOLD ? stage(p.activity()) : MUTED);
            gc.fillText(detail, textX, elbowY + 14);
        }
        gc.setGlobalAlpha(1);
    }

    /** Le cartouche, à la manière d'une planche scientifique : série, titre, notice et légende. */
    private void drawMonitor(GraphicsContext gc, Field field, double width, double height, Camera camera) {
        double x = width - 300, y = 70, w = 262;
        gc.setFill(INK.deriveColor(0, 1, 1, .78));
        gc.fillRect(x - 16, y - 22, w + 32, 360);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(MICRO);
        gc.setFill(ACCENT);
        gc.fillText(text("PROCESSUS.SÉRIE", "PROCESS.SERIES"), x, y);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(field == null ? "N. —" : "N. " + field.processCount(), x + w, y);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setStroke(ACCENT.deriveColor(0, 1, 1, .8));
        gc.setLineWidth(1);
        gc.strokeLine(x, y + 12, x + 18, y + 12);

        gc.setFill(TEXT);
        gc.setFont(DISPLAY);
        gc.fillText(text("ESPACE-TEMPS", "SPACETIME"), x, y + 44);
        gc.fillText(text("DES PROCESSUS", "OF PROCESSES"), x, y + 70);

        gc.setFont(CAPTION);
        gc.setFill(MUTED);
        String[] notice = language == UiLanguage.FRENCH
                ? new String[] {
                    "CHAQUE SPHÈRE EST UNE FAMILLE D'EXÉCUTABLES,",
                    "À L'ÉCHELLE DE SA MÉMOIRE.",
                    "SON CPU CHANGE LA COULEUR ET LA VITESSE DE SES",
                    "PARTICULES, ET OUVRE UN HORIZON NOIR QUI COURBE",
                    "LA LUMIÈRE. LIENS ET PAQUETS SONT SIMULÉS." }
                : new String[] {
                    "EACH SPHERE IS AN EXECUTABLE FAMILY, SCALED",
                    "BY ITS MEMORY USE.",
                    "ITS CPU LOAD CHANGES PARTICLE COLOR AND SPEED,",
                    "OPENING A BLACK HORIZON THAT BENDS LIGHT.",
                    "LINKS AND PACKETS ARE SIMULATED." };
        for (int i = 0; i < notice.length; i++) gc.fillText(notice[i], x, y + 96 + i * 14);

        // Les quatre couleurs de la charge
        double legendY = y + 180;
        gc.setFont(MICRO);
        for (int i = 0; i < STAGES.length; i++) {
            double sx = x + i * (w / 4.0);
            gc.setFill(STAGES[i]);
            gc.fillRect(sx, legendY, w / 4.0 - 6, 2);
            gc.setFill(MUTED);
            gc.fillText(stageName(i), sx, legendY + 15);
        }

        // Les quatre formes des sous-processus
        double shapesY = legendY + 42;
        gc.setFill(TEXT);
        gc.setFont(MICRO);
        gc.fillText(text("SOUS-PROCESSUS  ·  CPU EN % D'UN CŒUR", "SUBPROCESSES  ·  CPU AS % OF ONE CORE"), x, shapesY);
        gc.setFont(CAPTION);
        gc.setFill(MUTED);
        gc.fillText(text("POINTS  < 5      GÉODÉSIQUE  5 – 50", "POINTS  < 5      GEODESIC  5 – 50"), x, shapesY + 16);
        gc.fillText("CAGE  50 – 500      GLOBE  > 500", x, shapesY + 30);

        if (field != null) {
            gc.setFill(TEXT);
            gc.setFont(LABEL);
            gc.fillText(field.masses().size() + text(" FAMILLES", " FAMILIES"), x, shapesY + 60);
            gc.setFont(CAPTION);
            gc.setFill(MUTED);
            String memory = field.totalMemoryBytes().isPresent()
                    ? memory(field.memoryBytes()) + " / " + memory(field.totalMemoryBytes().getAsLong())
                    : memory(field.memoryBytes());
            gc.fillText(text("MÉMOIRE  ", "MEMORY  ") + memory + "   ·   " + field.cores()
                    + text(" CŒURS", " CORES"), x, shapesY + 76);
        }
        if (paused) {
            gc.setFont(MICRO);
            gc.setFill(ACCENT);
            gc.fillText(text("ANIMATION EN PAUSE", "ANIMATION PAUSED"), x, shapesY + 98);
        }

        if (field != null && field.totalMemoryBytes().isPresent()) {
            double r = SpacetimeLayout.bodyRadius((1L << 30) / (double) field.totalMemoryBytes().getAsLong()) * camera.zoom();
            if (r >= 4 && r < 160) {
                double sy = height - 86;
                gc.setStroke(TEXT.deriveColor(0, 1, 1, .8));
                gc.setLineWidth(1);
                gc.strokeLine(26, sy, 26 + r * 2, sy);
                gc.strokeLine(26, sy - 3, 26, sy + 3);
                gc.strokeLine(26 + r * 2, sy - 3, 26 + r * 2, sy + 3);
                gc.setFont(MICRO);
                gc.setFill(MUTED);
                gc.fillText(text("DIAMÈTRE · 1 GO", "DIAMETER · 1 GB"), 26, sy - 9);
            }
        }
    }

    /**
     * La fiche d'observation d'un objet cliqué : l'objet vivant, agrandi dans une fenêtre, avec
     * ce qui l'entoure, puis ses mesures et ce qu'elles veulent dire. Tout y est animé au même
     * rythme que la carte : c'est le même objet, vu de plus près.
     */
    private void drawDossier(GraphicsContext gc, Field field, Point point, double width) {
        Body body = bodies.get(point.owner());
        if (body == null) return;
        Mass mass = body.mass;
        boolean family = point.ring() < 0;
        int cores = field.cores();
        double x = width - 300, y = 70, w = 262;
        gc.setFill(INK.deriveColor(0, 1, 1, .9));
        gc.fillRect(x - 16, y - 22, w + 32, panelBottom - (y - 22));

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(MICRO);
        gc.setFill(ACCENT);
        gc.fillText(family ? text("OBJET.OBSERVÉ  ·  FAMILLE", "OBSERVED.OBJECT  ·  FAMILY")
                : text("OBJET.OBSERVÉ  ·  SOUS-PROCESSUS", "OBSERVED.OBJECT  ·  SUBPROCESS"), x, y);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("PID " + (family ? mass.principal().pid() : point.process().pid()), x + w, y);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(TEXT);
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
        String name = (family ? mass.name() : point.process().name()).toUpperCase(Locale.ROOT);
        gc.fillText(name, x, y + 24, w);

        // La fenêtre d'observation
        double vx = x, vy = y + 38, vw = w, vh = 236, cx = vx + vw / 2, cy = vy + vh / 2;
        gc.setFill(Color.web("#09090f"));
        gc.fillRect(vx, vy, vw, vh);
        gc.save();
        gc.beginPath();
        gc.rect(vx, vy, vw, vh);
        gc.clip();
        double charge = point.activity();
        if (family) {
            observeFamily(gc, field, body, point, cx, cy, vw, vh, charge);
        } else {
            observeSatellite(gc, body, point, cx, cy, vw, vh, charge);
        }
        matter.flush(gc);
        gc.restore();
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRect(vx + .5, vy + .5, vw - 1, vh - 1);
        // Des repères de visée aux coins, comme un instrument
        gc.setStroke(MUTED);
        for (double[] corner : new double[][] { {vx, vy, 1, 1}, {vx + vw, vy, -1, 1}, {vx, vy + vh, 1, -1}, {vx + vw, vy + vh, -1, -1} }) {
            gc.strokeLine(corner[0], corner[1], corner[0] + corner[2] * 10, corner[1]);
            gc.strokeLine(corner[0], corner[1], corner[0], corner[1] + corner[3] * 10);
        }
        gc.setFont(MICRO);
        gc.setFill(stage(charge));
        gc.fillText(stageName(stageIndex(charge)), vx + 8, vy + vh - 8);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(MUTED);
        gc.fillText(text("EN DIRECT", "LIVE"), vx + vw - 8, vy + vh - 8);
        gc.setTextAlign(TextAlignment.LEFT);

        // Les mesures
        double rowY = vy + vh + 26;
        List<String[]> rows = family ? familyRows(field, mass, point) : satelliteRows(field, body, point);
        for (String[] row : rows) {
            gc.setFont(MICRO);
            gc.setFill(MUTED);
            gc.fillText(row[0], x, rowY);
            gc.setFont(CAPTION);
            gc.setFill(TEXT);
            gc.fillText(row[1], x + 92, rowY, w - 92);
            rowY += 16;
        }

        // Ce que cela veut dire
        rowY += 10;
        gc.setFill(ACCENT);
        gc.fillRect(x, rowY - 8, 18, 2);
        gc.setFont(MICRO);
        gc.setFill(TEXT);
        gc.fillText(text("INTERPRÉTATION", "INTERPRETATION"), x + 26, rowY - 4);
        rowY += 14;
        gc.setFont(CAPTION);
        gc.setFill(MUTED);
        for (String sentence : family ? familyReading(field, mass, charge) : satelliteReading(point, charge)) {
            for (String line : wrap(sentence, 52)) {
                if (rowY > panelBottom - 12) return;
                gc.fillText(line, x, rowY);
                rowY += 13;
            }
            rowY += 4;
        }
    }

    /** La famille en grand : sa sphère, son horizon, son tourbillon et ses satellites à l'échelle. */
    private void observeFamily(GraphicsContext gc, Field field, Body body, Point point, double cx, double cy,
                               double vw, double vh, double charge) {
        Mass mass = body.mass;
        double horizonWorld = SpacetimeLayout.horizonRadius(mass.bodyRadius(), charge);
        double extent = Math.max(Math.max(mass.systemRadius(), horizonWorld * 1.6), mass.bodyRadius() * 3);
        double scale = Math.min(vw, vh) * .46 / extent;
        double r = Math.max(10, mass.bodyRadius() * scale);
        double horizon = horizonWorld * scale;
        localDust(point.id(), cx, cy, vw, vh, horizon);
        if (horizon > 2) {
            double outer = horizon * 1.35;
            gc.setFill(new RadialGradient(0, 0, cx, cy, outer, false, CycleMethod.NO_CYCLE,
                    new Stop(0, INK), new Stop(.72, INK.deriveColor(0, 1, 1, .97)), new Stop(1, INK.deriveColor(0, 1, 1, 0))));
            gc.fillOval(cx - outer, cy - outer, outer * 2, outer * 2);
            vortex(matter, point.owner() + ":vue", cx, cy, horizon, charge);
        }
        // Les satellites gardent leur place relative, et tournent avec ceux de la carte
        gc.setLineWidth(.6);
        for (Anomaly a : mass.anomalies()) {
            Point satellite = points.get(processId(a.process()));
            if (satellite == null) continue;
            double sx = cx + (satellite.worldX() - point.worldX()) * scale;
            double sy = cy + (satellite.worldY() - point.worldY()) * scale;
            gc.setStroke(stage(satellite.activity()).deriveColor(0, 1, 1, .18));
            gc.strokeLine(cx, cy, sx, sy);
            satellite(gc, new Point(satellite.id(), satellite.owner(), satellite.process(), sx, sy,
                    Math.max(2.4, a.radius() * scale), satellite.worldX(), satellite.worldY(), satellite.alpha(),
                    satellite.ring(), satellite.activity(), satellite.corePercent()), satellite.alpha());
        }
        core(gc, new Point(point.id(), point.owner(), point.process(), cx, cy, r, point.worldX(), point.worldY(),
                1, -1, charge, point.corePercent()), 1);
    }

    /** Le sous-processus en grand, sa forme lisible, relié à la sphère de sa famille. */
    private void observeSatellite(GraphicsContext gc, Body body, Point point, double cx, double cy,
                                  double vw, double vh, double charge) {
        localDust(point.id(), cx, cy, vw, vh, 0);
        Point parent = points.get("mass:" + body.mass.key());
        double px = cx - vw * .34, py = cy + vh * .3;
        if (parent != null) {
            gc.setStroke(stage(Math.max(charge, parent.activity())).deriveColor(0, 1, 1, .35));
            gc.setLineWidth(.7);
            gc.strokeLine(px, py, cx, cy);
            core(gc, new Point(parent.id(), parent.owner(), parent.process(), px, py, 16, parent.worldX(), parent.worldY(),
                    1, -1, parent.activity(), parent.corePercent()), .9);
        }
        satellite(gc, new Point(point.id(), point.owner(), point.process(), cx, cy, 58, point.worldX(), point.worldY(),
                1, point.ring(), charge, point.corePercent()), 1);
    }

    /** Une poussière propre à la fenêtre d'observation, qui dérive et que l'horizon repousse. */
    private void localDust(String key, double cx, double cy, double vw, double vh, double horizon) {
        for (int i = 0; i < 320; i++) {
            long seed = mix(key.hashCode() * 131L + i);
            double x = cx + (unit(seed, 0) - .5) * vw + (noise(unit(seed, 10) * 40, time * .07) - .5) * 24;
            double y = cy + (unit(seed, 20) - .5) * vh + (noise(unit(seed, 30) * 40 + 9, time * .07) - .5) * 24;
            double dx = x - cx, dy = y - cy, d = Math.max(1e-3, Math.hypot(dx, dy));
            double lensed = 0;
            if (horizon > 0) {
                double einstein = horizon * 1.12, reach = einstein * 2.6;
                if (d < reach) {
                    lensed = smooth(reach, einstein * 1.4, d);
                    double image = d + ((d + Math.sqrt(d * d + 4 * einstein * einstein)) / 2 - d) * lensed;
                    x = cx + dx / d * image;
                    y = cy + dy / d * image;
                }
            }
            double twinkle = .6 + .4 * Math.sin(time * (.8 + 2 * unit(seed, 40)) + i);
            matter.add(x, y, lensed > .5 ? 1.5 : 1.1, lensed > .15 ? .2 : -1, (.22 + .5 * lensed) * twinkle);
        }
    }

    private List<String[]> familyRows(Field field, Mass mass, Point point) {
        int[] shapes = new int[4];
        Anomaly busiest = null;
        for (Anomaly a : mass.anomalies()) {
            shapes[satelliteType(a.cpuShare() * field.cores() * 100)]++;
            if (busiest == null || a.cpuShare() > busiest.cpuShare()) busiest = a;
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { text("MÉMOIRE", "MEMORY"), mass.memoryKnown()
                ? memory(mass.memoryBytes()) + "  ·  " + percent(mass.memoryShare())
                        + text(" DE LA RAM", " OF RAM") : text("INCONNUE", "UNKNOWN") });
        rows.add(new String[] { "CPU", field.cpuSampled()
                ? percent(mass.cpuShare()) + text(" DE LA MACHINE  ·  ", " OF MACHINE  ·  ")
                        + String.format(numberLocale(), text("%.2f CŒUR", "%.2f CORE"), mass.cpuShare() * field.cores())
                : text("EN ATTENTE DU 2E RELEVÉ", "WAITING FOR SECOND SAMPLE") });
        rows.add(new String[] { text("PROCESSUS", "PROCESSES"), mass.processCount()
                + text("  ·  PRINCIPAL PID ", "  ·  MAIN PID ") + mass.principal().pid() });
        rows.add(new String[] { text("FORMES", "SHAPES"), shapes[0] + " POINTS · " + shapes[1]
                + text(" GÉOD. · ", " GEOD. · ") + shapes[2] + " CAGES · " + shapes[3] + " GLOBES" });
        double horizon = SpacetimeLayout.horizonRadius(mass.bodyRadius(), point.activity());
        rows.add(new String[] { "HORIZON", horizon > 0
                ? String.format(numberLocale(), text("OUVERT  ·  %.1f × LE RAYON DU CORPS", "OPEN  ·  %.1f × BODY RADIUS"),
                        horizon / mass.bodyRadius()) : text("FERMÉ", "CLOSED") });
        if (busiest != null && busiest.cpuShare() > 0) {
            rows.add(new String[] { text("PLUS ACTIF", "MOST ACTIVE"), busiest.process().name().toUpperCase(Locale.ROOT) + "  ·  PID "
                    + busiest.process().pid() + "  ·  " + String.format(numberLocale(), text("%.0f %% D'UN CŒUR", "%.0f %% OF ONE CORE"),
                            busiest.cpuShare() * field.cores() * 100) });
        }
        return rows;
    }

    private List<String[]> satelliteRows(Field field, Body body, Point point) {
        Anomaly anomaly = null;
        for (Anomaly a : body.mass.anomalies()) {
            if (processId(a.process()).equals(point.id())) anomaly = a;
        }
        List<String[]> rows = new ArrayList<>();
        ProcessSnapshot process = point.process();
        rows.add(new String[] { text("FAMILLE", "FAMILY"), body.mass.name().toUpperCase(Locale.ROOT)
                + "  ·  " + body.mass.processCount() + text(" PROCESSUS", " PROCESSES") });
        rows.add(new String[] { "PARENT", process.parentPid().isPresent() ? "PID " + process.parentPid().getAsLong() : text("INCONNU", "UNKNOWN") });
        rows.add(new String[] { text("MÉMOIRE", "MEMORY"), process.memoryBytes().isPresent()
                ? memory(process.memoryBytes().getAsLong()) : text("INCONNUE", "UNKNOWN") });
        rows.add(new String[] { "CPU", field.cpuSampled()
                ? String.format(numberLocale(), text("%.1f %% D'UN CŒUR", "%.1f %% OF ONE CORE"), point.corePercent())
                : text("EN ATTENTE DU 2E RELEVÉ", "WAITING FOR SECOND SAMPLE") });
        rows.add(new String[] { text("FORME", "SHAPE"), shapeName(satelliteType(point.corePercent())) });
        if (anomaly != null) {
            double period = TAU / SpacetimeLayout.angularSpeed(anomaly.orbitRadius(), body.depth);
            rows.add(new String[] { text("ORBITE", "ORBIT"), String.format(numberLocale(),
                    text("ANNEAU %d  ·  UN TOUR EN %.0f S", "RING %d  ·  ONE ORBIT IN %.0f S"), anomaly.ring() + 1, period) });
        }
        return rows;
    }

    /** Ce que disent les mesures d'une famille, en phrases. */
    private List<String> familyReading(Field field, Mass mass, double charge) {
        List<String> reading = new ArrayList<>();
        if (!field.cpuSampled()) {
            reading.add(text("Le CPU se mesure entre deux relevés : la charge apparaîtra dans quelques secondes.",
                    "CPU is measured between two samples; the load will appear in a few seconds."));
        } else {
            reading.add(language == UiLanguage.FRENCH
                    ? switch (stageIndex(charge)) {
                        case 0 -> "Au repos : aucun horizon ne s'ouvre, l'espace autour de la famille reste calme.";
                        case 1 -> "Charge modérée : un horizon s'ouvre et commence à aspirer la poussière voisine.";
                        case 2 -> "Charge soutenue : l'horizon s'élargit, le tourbillon accélère et les particules s'échauffent.";
                        default -> "Charge intense : l'horizon domine son territoire, la famille occupe près de deux cœurs ou plus.";
                    }
                    : switch (stageIndex(charge)) {
                        case 0 -> "Idle: no horizon opens, and space around the family remains calm.";
                        case 1 -> "Moderate load: a horizon opens and begins pulling in nearby dust.";
                        case 2 -> "High load: the horizon expands, the vortex accelerates, and particles heat up.";
                        default -> "Intense load: the horizon dominates its territory; the family uses nearly two cores or more.";
                    });
        }
        if (mass.memoryShare() >= .1) {
            reading.add(text("Poids lourd : plus de 10 % de la mémoire de la machine.",
                    "Heavyweight: more than 10% of the machine's memory."));
        } else if (mass.memoryShare() < .005 && charge >= SpacetimeLayout.HORIZON_THRESHOLD) {
            reading.add(text("Petit mais actif : peu de mémoire, et pourtant un horizon bien ouvert.",
                    "Small but active: little memory, yet a wide-open horizon."));
        }
        if (mass.anomalies().size() >= 10) {
            reading.add(text("Famille nombreuse : " + mass.anomalies().size()
                            + " sous-processus en orbite, souvent le signe d'onglets, d'extensions ou de services.",
                    "Large family: " + mass.anomalies().size()
                            + " orbiting subprocesses, often indicating tabs, extensions, or services."));
        } else if (mass.anomalies().isEmpty()) {
            reading.add(text("Processus isolé : aucun sous-processus de même exécutable.",
                    "Isolated process: no subprocess from the same executable."));
        }
        return reading;
    }

    private List<String> satelliteReading(Point point, double charge) {
        List<String> reading = new ArrayList<>();
        switch (satelliteType(point.corePercent())) {
            case 0 -> reading.add(text("Presque inactif : moins de 5 % d'un cœur, il n'apparaît qu'en nuage de points.",
                    "Nearly idle: below 5% of one core, it appears only as a point cloud."));
            case 1 -> reading.add(text("Actif : entre 5 et 50 % d'un cœur, sa structure géodésique se dessine.",
                    "Active: between 5% and 50% of one core, its geodesic structure appears."));
            case 2 -> reading.add(text("Très actif : plus de la moitié d'un cœur, sa cage projette des éclats.",
                    "Very active: above half a core, its cage projects sparks."));
            default -> reading.add(text("Extrême : plus de cinq cœurs à lui seul, un globe dense.",
                    "Extreme: more than five cores by itself, shown as a dense globe."));
        }
        if (charge >= .5) reading.add(text("Sa couleur s'échauffe : il pèse dans la charge de toute sa famille.",
                "Its color heats up: it contributes heavily to its family's load."));
        reading.add(text("Les liens qui le relient à ses voisins sont simulés : ils ne mesurent aucun échange réel.",
                "Links to neighboring processes are simulated; they do not measure actual exchanges."));
        return reading;
    }

    /**
     * Le palier de charge nommé, une seule règle pour la fenêtre et l'interprétation : sous le seuil
     * de l'horizon c'est le repos, puis modérée, soutenue et intense par tiers.
     */
    private static int stageIndex(double charge) {
        if (charge < SpacetimeLayout.HORIZON_THRESHOLD) return 0;
        return charge < 1 / 3.0 ? 1 : charge < 2 / 3.0 ? 2 : 3;
    }

    private static List<String> wrap(String text, int limit) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > limit) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** La charge totale, en bandeau vertical, comme la cote d'une planche. */
    private void drawLoad(GraphicsContext gc, Field field, double width, double height) {
        if (field == null || !field.cpuSampled()) return;
        double total = 0;
        for (Mass mass : field.masses()) total += mass.cpuShare();
        String text = "CPU " + percent(Math.min(1, total));
        double boxHeight = text.length() * 7.2 + 18;
        double x = width - 34, y = height - 70;
        gc.save();
        gc.translate(x, y);
        gc.rotate(-90);
        gc.setFill(ACCENT);
        gc.fillRect(0, -12, boxHeight, 17);
        gc.setFill(INK);
        gc.setFont(MICRO);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(text, 9, 0);
        gc.restore();
    }

    private void drawInspector(GraphicsContext gc, Field field, Point point, double height, double width) {
        Mass mass = bodies.get(point.owner()).mass;
        boolean family = point.ring() < 0;
        String name = family ? mass.name().toUpperCase(Locale.ROOT) + "  ·  " + mass.processCount()
                + text(" PROCESSUS", " PROCESSES")
                : point.process().name().toUpperCase(Locale.ROOT) + "  ·  PID " + point.process().pid();
        String cpu = field.cpuSampled()
                ? String.format(numberLocale(), text("%s DE LA MACHINE (%.0f %% D'UN CŒUR)", "%s OF MACHINE (%.0f %% OF ONE CORE)"),
                        percent(point.corePercent() / 100 / Math.max(1, field.cores())), point.corePercent())
                : text("EN ATTENTE", "WAITING");
        String info = family
                ? text("FAMILLE D'EXÉCUTABLES  ·  ", "EXECUTABLE FAMILY  ·  ")
                    + (mass.memoryKnown() ? memory(mass.memoryBytes()) : text("MÉMOIRE INCONNUE", "MEMORY UNKNOWN"))
                    + "  ·  CPU " + cpu
                : text("FAMILLE ", "FAMILY ") + mass.name().toUpperCase(Locale.ROOT) + "  ·  PARENT PID "
                    + (point.process().parentPid().isPresent() ? point.process().parentPid().getAsLong() : text("INCONNU", "UNKNOWN"))
                    + "  ·  " + (point.process().memoryBytes().isPresent() ? memory(point.process().memoryBytes().getAsLong())
                            : text("MÉMOIRE INCONNUE", "MEMORY UNKNOWN"))
                    + "  ·  CPU " + cpu;
        double w = Math.min(width - 380, 760), x = 26, y = height - 118;
        gc.setFill(INK.deriveColor(0, 1, 1, .85));
        gc.fillRect(x - 12, y - 24, w + 24, 58);
        gc.setFill(ACCENT);
        gc.fillRect(x, y - 16, 18, 2);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(LABEL);
        gc.setFill(TEXT);
        gc.fillText(name, x, y + 4, w);
        gc.setFont(CAPTION);
        gc.setFill(MUTED);
        gc.fillText(info, x, y + 22, w);
    }

    private void drawHud(GraphicsContext gc, double width, double height, String status) {
        gc.setFont(MICRO);
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(status == null ? "" : status.toUpperCase(Locale.ROOT), 26, height - 26, Math.max(100, width * .34));
        gc.setTextAlign(TextAlignment.RIGHT);
        String help = width > 1200
                ? text("MOLETTE  ZOOM     GLISSER  EXPLORER     CLIC DROIT  INCLINER     DOUBLE-CLIC  APPROCHER",
                        "WHEEL  ZOOM     DRAG  EXPLORE     RIGHT-CLICK  TILT     DOUBLE-CLICK  MOVE CLOSER")
                : text("MOLETTE  ZOOM     DOUBLE-CLIC  APPROCHER",
                        "WHEEL  ZOOM     DOUBLE-CLICK  MOVE CLOSER");
        gc.fillText(help, width - 60, height - 26);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRect(.5, .5, width - 1, height - 1);
    }

    // --- Formes -------------------------------------------------------------------------------

    private static void icosahedron(List<double[]> vertices, List<int[]> faces) {
        double t = (1 + Math.sqrt(5)) / 2;
        double[][] v = { {-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t},
                {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1} };
        for (double[] p : v) vertices.add(normalized(p));
        int[][] f = { {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4},
                {11, 10, 2}, {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1} };
        faces.addAll(Arrays.asList(f));
    }

    /** Coupe chaque triangle en quatre, en ramenant les milieux sur la sphère. */
    private static List<int[]> subdivide(List<double[]> vertices, List<int[]> faces) {
        Map<Long, Integer> middles = new HashMap<>();
        List<int[]> result = new ArrayList<>();
        for (int[] face : faces) {
            int ab = middle(vertices, middles, face[0], face[1]);
            int bc = middle(vertices, middles, face[1], face[2]);
            int ca = middle(vertices, middles, face[2], face[0]);
            result.add(new int[] { face[0], ab, ca });
            result.add(new int[] { face[1], bc, ab });
            result.add(new int[] { face[2], ca, bc });
            result.add(new int[] { ab, bc, ca });
        }
        return result;
    }

    private static int middle(List<double[]> vertices, Map<Long, Integer> middles, int a, int b) {
        long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
        return middles.computeIfAbsent(key, ignored -> {
            double[] p = vertices.get(a), q = vertices.get(b);
            vertices.add(normalized(new double[] { p[0] + q[0], p[1] + q[1], p[2] + q[2] }));
            return vertices.size() - 1;
        });
    }

    private static int[] edgesOf(List<int[]> faces) {
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        for (int[] face : faces) {
            for (int i = 0; i < 3; i++) {
                int a = face[i], b = face[(i + 1) % 3];
                seen.add(((long) Math.min(a, b) << 32) | Math.max(a, b));
            }
        }
        int[] edges = new int[seen.size() * 2];
        int i = 0;
        for (long key : seen) {
            edges[i++] = (int) (key >>> 32);
            edges[i++] = (int) key;
        }
        return edges;
    }

    /** La cage : un sommet au centre de chaque triangle, relié à ceux des triangles voisins. */
    private static Mesh dualOf(List<double[]> vertices, List<int[]> faces) {
        double[][] centres = new double[faces.size()][];
        Map<Long, List<Integer>> sharing = new HashMap<>();
        for (int f = 0; f < faces.size(); f++) {
            int[] face = faces.get(f);
            double[] a = vertices.get(face[0]), b = vertices.get(face[1]), c = vertices.get(face[2]);
            centres[f] = normalized(new double[] { a[0] + b[0] + c[0], a[1] + b[1] + c[1], a[2] + b[2] + c[2] });
            for (int i = 0; i < 3; i++) {
                int p = face[i], q = face[(i + 1) % 3];
                sharing.computeIfAbsent(((long) Math.min(p, q) << 32) | Math.max(p, q), ignored -> new ArrayList<>()).add(f);
            }
        }
        List<Integer> pairs = new ArrayList<>();
        for (List<Integer> both : sharing.values()) {
            if (both.size() == 2) {
                pairs.add(both.get(0));
                pairs.add(both.get(1));
            }
        }
        return new Mesh(centres, pairs.stream().mapToInt(Integer::intValue).toArray());
    }

    private static double[] normalized(double[] p) {
        double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        return new double[] { p[0] / length, p[1] / length, p[2] / length };
    }

    /** Des points régulièrement répartis sur la sphère, en spirale de Fibonacci. */
    private static double[][] spherePoints(int count) {
        return SPHERE_POINTS.computeIfAbsent(count, n -> {
            double[][] result = new double[n][];
            for (int i = 0; i < n; i++) {
                double y = 1 - 2 * (i + .5) / n;
                double radius = Math.sqrt(1 - y * y);
                double angle = i * GOLDEN_ANGLE;
                result[i] = new double[] { Math.cos(angle) * radius, y, Math.sin(angle) * radius };
            }
            return result;
        });
    }

    /** Les paires de voisins d'une spirale de Fibonacci : chaque point relié à ses trois plus proches. */
    private static int[] plexus(int count) {
        return PLEXUS.computeIfAbsent(count, n -> {
            double[][] p = spherePoints(n);
            java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
            for (int i = 0; i < n; i++) {
                int[] best = { -1, -1, -1 };
                double[] dist = { 9, 9, 9 };
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    double dx = p[i][0] - p[j][0], dy = p[i][1] - p[j][1], dz = p[i][2] - p[j][2];
                    double d = dx * dx + dy * dy + dz * dz;
                    for (int k = 0; k < 3; k++) {
                        if (d < dist[k]) {
                            for (int m = 2; m > k; m--) { dist[m] = dist[m - 1]; best[m] = best[m - 1]; }
                            dist[k] = d;
                            best[k] = j;
                            break;
                        }
                    }
                }
                for (int j : best) if (j >= 0) seen.add(((long) Math.min(i, j) << 32) | Math.max(i, j));
            }
            int[] pairs = new int[seen.size() * 2];
            int k = 0;
            for (long key : seen) {
                pairs[k++] = (int) (key >>> 32);
                pairs[k++] = (int) key;
            }
            return pairs;
        });
    }

    /** Les tailles de nuage mises en cache : chaque sphère réutilise la plus proche. */
    private static int bucket(int wanted) {
        int size = 48;
        while (size < wanted && size < 1_536) size *= 2;
        return size;
    }

    // --- Utilitaires ---------------------------------------------------------------------------

    public String hitTest(double x, double y) {
        Point point = nearest(x, y);
        return point == null ? null : point.id();
    }

    private Point nearest(double x, double y) {
        Point best = null;
        double score = Double.POSITIVE_INFINITY;
        for (Point p : points.values()) {
            if (p.alpha() < .35) continue;
            double reach = Math.max(4, p.radius()) + 6;
            double distance = Math.hypot(x - p.x(), y - p.y()) / reach;
            if (distance < 1 && distance < score) {
                score = distance;
                best = p;
            }
        }
        return best;
    }

    /** La charge entre 0 et 1, comptée en cœurs : deux cœurs pleins suffisent à la saturer. */
    private static double activity(double cpuShare, int cores) {
        return Math.min(1, Math.sqrt(Math.max(0, cpuShare) * Math.max(1, cores) / 2));
    }

    /** La couleur d'une charge, entre les quatre couleurs du repos à l'intense. */
    private static Color stage(double charge) {
        double v = Math.max(0, Math.min(1, charge)) * (STAGES.length - 1);
        int i = (int) Math.min(STAGES.length - 2, Math.floor(v));
        return STAGES[i].interpolate(STAGES[i + 1], v - i);
    }

    private static double smooth(double edge0, double edge1, double value) {
        double t = Math.max(0, Math.min(1, (value - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    /** Du hasard reproductible, sans créer d'objet à chaque image. */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (z ^ (z >>> 31)) & Long.MAX_VALUE;
    }

    private static double unit(long seed, int shift) {
        return ((seed >>> shift) & 0x3FF) / 1023.0;
    }

    /** Bruit de valeur lissé, entre 0 et 1. */
    private static double noise(double x, double y) {
        long xi = (long) Math.floor(x), yi = (long) Math.floor(y);
        double tx = x - xi, ty = y - yi;
        double u = tx * tx * (3 - 2 * tx), v = ty * ty * (3 - 2 * ty);
        double top = lattice(xi, yi) + (lattice(xi + 1, yi) - lattice(xi, yi)) * u;
        double bottom = lattice(xi, yi + 1) + (lattice(xi + 1, yi + 1) - lattice(xi, yi + 1)) * u;
        return top + (bottom - top) * v;
    }

    private static double lattice(long x, long y) {
        return (mix(x * 73_856_093L ^ y * 19_349_663L) & 0xFFFF) / 65_535.0;
    }

    private int cores() {
        return lastField == null ? 1 : lastField.cores();
    }

    private static boolean onScreen(double x, double y, double margin, double width, double height) {
        return x > -margin && y > -margin && x < width + margin && y < height + margin;
    }

    private static String processId(ProcessSnapshot process) {
        return "proc:" + process.pid() + ":" + process.startTime().map(Object::toString).orElse("unknown");
    }

    private static boolean matches(Mass mass, String query) {
        if (query == null || query.isBlank()) return false;
        String text = query.trim().toLowerCase(Locale.ROOT);
        return mass.name().toLowerCase(Locale.ROOT).contains(text) || Long.toString(mass.principal().pid()).equals(text)
                || mass.anomalies().stream().anyMatch(a -> Long.toString(a.process().pid()).equals(text));
    }

    private String memory(long bytes) {
        return bytes >= (1L << 30)
                ? String.format(numberLocale(), text("%.2f GO", "%.2f GB"), bytes / (double) (1L << 30))
                : String.format(numberLocale(), text("%.0f MO", "%.0f MB"), bytes / (double) (1L << 20));
    }

    private String percent(double share) {
        return String.format(numberLocale(), "%.1f %%", share * 100);
    }

    private Locale numberLocale() {
        return language == UiLanguage.FRENCH ? Locale.FRANCE : Locale.US;
    }

    private String stageName(int index) {
        return (language == UiLanguage.FRENCH ? STAGE_NAMES_FR : STAGE_NAMES_EN)[index];
    }

    private String shapeName(int index) {
        return language == UiLanguage.FRENCH
                ? new String[] { "NUAGE DE POINTS  ·  < 5 %", "GÉODÉSIQUE  ·  5 – 50 %",
                    "CAGE  ·  50 – 500 %", "GLOBE DENSE  ·  > 500 %" }[index]
                : new String[] { "POINT CLOUD  ·  < 5 %", "GEODESIC  ·  5 – 50 %",
                    "CAGE  ·  50 – 500 %", "DENSE GLOBE  ·  > 500 %" }[index];
    }
}
