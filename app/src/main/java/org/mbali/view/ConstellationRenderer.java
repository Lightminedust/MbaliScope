package org.mbali.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkLink;
import org.mbali.model.PortEndpoint;
import org.mbali.model.Presence;
import org.mbali.view.ConstellationLayout.Constellation;
import org.mbali.view.ConstellationLayout.Satellite;
import org.mbali.view.ConstellationLayout.SatelliteKind;
import org.mbali.view.ConstellationLayout.Star;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * La constellation du réseau, dessinée en particules et en traits fins, dans le style de la vue
 * des processus.
 *
 * Les principes de la carte ne changent pas — des systèmes indépendants, l'orbite qui dit
 * l'appartenance, des tailles vraies — seule la matière change. Chaque appareil est une sphère
 * faite de couches, et ses couches disent ce que l'on sait de lui :
 *   — la sphère bleue translucide est l'appareil lui-même ;
 *   — la cage à bulles marque un concentrateur, par lequel d'autres appareils passent ;
 *   — les amas d'or sont ses ports ouverts ;
 *   — le noyau vert dit qu'il est identifié, qu'il a livré un nom ;
 *   — les piques sont ses liaisons : plus il en porte, plus il en a.
 * Un serveur distant n'est qu'une structure de fil, sans matière ; un appareil Bluetooth, une
 * émission de piques. Le cortège reprend les mêmes formes en petit : carte réseau en sphère à
 * cercles, périphérique en fil, port en sphère d'or pointillée, service en aigrette.
 *
 * Le cortège tourne sur des anneaux tracés en pointillés, comme les orbites des appareils. Les
 * liens entre objets voisins s'allument et s'éteignent, décoratifs ; seuls les traits entre
 * systèmes suivent le modèle.
 */
public final class ConstellationRenderer {

    /** Ce que l'utilisateur a choisi de voir. */
    public record Filter(String query, Set<SatelliteKind> kinds) {
        public static Filter all() {
            return new Filter("", EnumSet.allOf(SatelliteKind.class));
        }

        public boolean showsKind(SatelliteKind kind) {
            return kinds.contains(kind);
        }

        public boolean matches(Satellite node) {
            if (query == null || query.isBlank()) {
                return true;
            }
            String needle = query.toLowerCase(Locale.ROOT);
            return node.label().toLowerCase(Locale.ROOT).contains(needle)
                    || node.detail().toLowerCase(Locale.ROOT).contains(needle);
        }

        public boolean searching() {
            return query != null && !query.isBlank();
        }
    }

    /** État visible du travail de découverte en cours. Une progression négative est indéterminée. */
    public record Loading(String title, String detail, double progress) {
        public Loading {
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
            progress = Math.max(-1, Math.min(1, progress));
        }

        public static Loading indeterminate(String title, String detail) {
            return new Loading(title, detail, -1);
        }

        public static Loading progress(String title, String detail, double progress) {
            return new Loading(title, detail, progress);
        }

        boolean determinate() {
            return progress >= 0;
        }
    }

    /**
     * L'état du battement, tel que le rendu a besoin de le connaître. La vue ignore
     * volontairement d'où il vient : elle ne dépend d'aucune classe de service.
     */
    public record Pulse(int latencyMs, int neighbours, long ageMillis, double[] history) {
    }

    private static final Color INK = Color.web("#040407");
    private static final Color TEXT = Color.web("#f2f2f5");
    private static final Color MUTED = Color.web("#8c8c98");
    private static final Color FAINT = Color.web("#3a3a44");
    private static final Color ACCENT = Color.web("#ff2d55");

    /** Les couleurs de la planche : sphère bleue, fil blanc, or, noyau vert, pointes cyan, cage. */
    private static final Color BLUE = Color.web("#3f9dff");
    private static final Color WHITE = Color.web("#dde6f0");
    private static final Color GOLD = Color.web("#f4c24f");
    private static final Color GREEN = Color.web("#3dffa2");
    private static final Color CYAN = Color.web("#7fe8ff");
    private static final Color TEAL = Color.web("#5ad6b4");
    private static final Color DUST = Color.web("#9aa3b8");

    private static final int P_BLUE = 0, P_WHITE = 1, P_GOLD = 2, P_GREEN = 3, P_CYAN = 4, P_TEAL = 5,
            P_DUST = 6, P_ACCENT = 7;
    private static final Color[] PALETTE = { BLUE, WHITE, GOLD, GREEN, CYAN, TEAL, DUST, ACCENT };

    private static final Font DISPLAY = Font.font("Segoe UI", FontWeight.BOLD, 21);
    private static final Font HEADING = Font.font("Segoe UI", FontWeight.BOLD, 16);
    private static final Font LABEL = Font.font("Segoe UI", FontWeight.BOLD, 11.5);
    private static final Font CAPTION = Font.font("Segoe UI", 9.5);
    private static final Font MICRO = Font.font("Segoe UI", FontWeight.BOLD, 9);

    private static final double TAU = Math.PI * 2;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

    /** Plancher résiduel : un objet sous le pixel ne doit pas disparaître pour autant. */
    private static final double NEVER_BELOW_PX = 2.2;

    /** Poussière de l'espace : un grain tous les DUST_PX pixels environ, quel que soit le zoom. */
    private static final double DUST_STEP = 2_500;
    private static final double DUST_PX = 12;
    private static final int MAX_DUST = 20_000;

    private static final double MONITOR_BOTTOM = 560;

    /** Particules regroupées par couleur et par opacité, dessinées carré par carré. */
    private static final int LEVELS = 6;
    private static final Color[][] SHADES = new Color[PALETTE.length][LEVELS];

    static {
        for (int p = 0; p < PALETTE.length; p++) {
            for (int l = 0; l < LEVELS; l++) {
                SHADES[p][l] = PALETTE[p].deriveColor(0, 1, 1, (l + 1) / (double) LEVELS);
            }
        }
    }

    private static final class Particles {
        final double[][] data = new double[PALETTE.length * LEVELS][192];
        final int[] counts = new int[PALETTE.length * LEVELS];

        void add(double x, double y, double size, int colour, double alpha) {
            if (alpha < .5 / LEVELS) return;
            int l = (int) Math.min(LEVELS - 1, Math.max(0, Math.round(alpha * LEVELS - 1)));
            int bucket = colour * LEVELS + l;
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
                // Un carré par particule, sans chemin : un chemin géant est rastérisé en logiciel
                gc.setFill(SHADES[bucket / LEVELS][bucket % LEVELS]);
                for (int i = 0; i < count; i += 3) {
                    double s = values[i + 2];
                    gc.fillRect(values[i] - s / 2, values[i + 1] - s / 2, s, s);
                }
                counts[bucket] = 0;
            }
        }
    }

    // --- Formes de sphère, construites une fois ---------------------------------------------------

    private static final double[][] ICOSA;
    private static final double[][] DODECA;
    private static final int[] DODECA_EDGES;
    private static final double[][] GEODESIC;
    private static final int[] GEODESIC_EDGES;
    /** Les quatre cercles de la sphère bleue : centrés sur les sommets d'un tétraèdre. */
    private static final double[][][] CIRCLES;

    static {
        List<double[]> vertices = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        icosahedron(vertices, faces);
        ICOSA = vertices.toArray(double[][]::new);
        DODECA = new double[faces.size()][];
        DODECA_EDGES = dual(vertices, faces, DODECA);
        List<int[]> fine = subdivide(vertices, faces);
        GEODESIC = vertices.toArray(double[][]::new);
        GEODESIC_EDGES = edgesOf(fine);

        double[][] tetra = { {1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1} };
        double rho = Math.toRadians(50);
        CIRCLES = new double[4][41][];
        for (int c = 0; c < 4; c++) {
            double[] d = normalized(tetra[c]);
            double[] helper = Math.abs(d[0]) < .9 ? new double[] {1, 0, 0} : new double[] {0, 1, 0};
            double[] u = normalized(cross(d, helper));
            double[] v = cross(d, u);
            for (int k = 0; k <= 40; k++) {
                double phi = k * TAU / 40;
                double cu = Math.cos(phi) * Math.sin(rho), cv = Math.sin(phi) * Math.sin(rho), cd = Math.cos(rho);
                CIRCLES[c][k] = new double[] { d[0] * cd + u[0] * cu + v[0] * cv,
                        d[1] * cd + u[1] * cu + v[1] * cv, d[2] * cd + u[2] * cu + v[2] * cv };
            }
        }
    }

    private static final Map<Integer, double[][]> SPHERE_POINTS = new HashMap<>();
    private static final Map<Integer, int[]> PLEXUS = new HashMap<>();

    // --- État d'une image -------------------------------------------------------------------------

    /** Un objet placé : un appareil (satellite null) ou un objet de son cortège. */
    private record Node(String id, Star star, Satellite satellite, double worldX, double worldY,
                        double x, double y, double radius, boolean match) {
        boolean device() {
            return satellite == null;
        }
    }

    private record Box(double x, double y, double width, double height) {
        boolean hits(Box b) {
            return x < b.x + b.width && x + width > b.x && y < b.y + b.height && y + height > b.y;
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Node> byId = new HashMap<>();
    private final Map<String, List<Node>> groups = new HashMap<>();
    private final Map<String, Integer> carried = new HashMap<>();
    private final Map<SatelliteKind, int[]> census = new EnumMap<>(SatelliteKind.class);
    /** Visibilité de chaque lien de voisinage, qui apparaît et disparaît en fondu. */
    private final Map<String, Double> linkFade = new HashMap<>();
    private final List<Box> labels = new ArrayList<>();
    private final Particles space = new Particles();
    private final Particles matter = new Particles();
    private final double[] turned = new double[3];
    private final double[] meshX = new double[64];
    private final double[] meshY = new double[64];
    private final double[] meshZ = new double[64];
    private final double[] ringX = new double[41];
    private final double[] ringY = new double[41];
    private final double[] ringZ = new double[41];

    private Pulse pulse;
    private UiLanguage language = UiLanguage.FRENCH;
    private double time;
    private double lastSeconds = -1;
    private double frameDt;
    private double panelBottom = MONITOR_BOTTOM;

    public void draw(GraphicsContext gc, Constellation map, Camera camera, double width, double height,
                     double seconds, double mouseX, double mouseY, boolean mouseInside,
                     String statusText, String selectedId, Filter filter, Loading loading) {
        if (width < 2 || height < 2) return;
        Filter active = filter == null ? Filter.all() : filter;
        frameDt = lastSeconds < 0 ? 0 : Math.max(0, Math.min(.1, seconds - lastSeconds));
        lastSeconds = seconds;
        time = seconds;

        gc.setGlobalAlpha(1);
        gc.setFill(INK);
        gc.fillRect(0, 0, width, height);
        census.clear();

        if (map != null) {
            place(map, camera, active);
            drawDust(camera, width, height);
            drawOrbits(map, camera, width, height);
            space.flush(gc);

            Node hovered = mouseInside ? nearest(mouseX, mouseY) : null;
            Node observed = selectedId == null ? null : byId.get(selectedId);
            drawSystemLinks(gc, map, width, height);
            drawDeviceLinks(gc, map, width, height);
            drawNeighbourLinks(gc, width, height);
            drawObjects(gc, width, height);
            matter.flush(gc);
            drawSelection(gc, selectedId);

            panelBottom = observed == null ? MONITOR_BOTTOM : Math.min(height - 70, 700);
            drawLabels(gc, map, selectedId, hovered == null ? null : hovered.id(), active, width, height);
            if (observed != null) {
                drawDossier(gc, map, observed, width);
            } else {
                drawMonitor(gc, map, active, width);
            }
            drawLatency(gc, width, height);
            Node shown = hovered;
            if (shown != null && observed == null) {
                drawInspector(gc, shown, width, height);
            }
        }
        if (loading != null) {
            drawLoading(gc, width, height, loading, map == null || map.stars().isEmpty());
        }
        drawHud(gc, width, height, statusText);
    }

    /** Le battement, publié depuis le thread graphique. */
    public void setPulse(Pulse pulse) {
        this.pulse = pulse;
    }

    public void setLanguage(UiLanguage language) {
        this.language = language == null ? UiLanguage.FRENCH : language;
    }

    private String text(String french, String english) {
        return language.text(french, english);
    }

    public String hitTest(Constellation map, Camera camera, double screenX, double screenY) {
        Node node = nearest(screenX, screenY);
        return node == null ? null : node.id();
    }

    // --- Placement --------------------------------------------------------------------------------

    /** Une seule évaluation des positions par image, réutilisée partout. */
    private void place(Constellation map, Camera camera, Filter filter) {
        nodes.clear();
        byId.clear();
        groups.clear();
        carried.clear();
        double zoom = camera.zoom();
        String needle = filter.searching() ? filter.query().toLowerCase(Locale.ROOT) : null;
        for (Star star : map.stars().values()) {
            Device device = star.device();
            if (star.parentId() != null) carried.merge(star.parentId(), 1, Integer::sum);
            double x = ConstellationLayout.starX(star, time);
            double y = ConstellationLayout.starY(star, time);
            boolean match = needle != null && device.getName() != null
                    && device.getName().toLowerCase(Locale.ROOT).contains(needle);
            List<Node> group = new ArrayList<>();
            Node centre = new Node(device.getId(), star, null, x, y, camera.screenX(x), camera.screenY(y),
                    // Une balise Bluetooth reste lisible de loin : ses ondes ont besoin de quelques pixels
                    Math.max(device.getType() == DeviceType.BLUETOOTH ? 5 : NEVER_BELOW_PX, star.coreRadius() * zoom), match);
            add(centre, group);
            for (Satellite satellite : star.satellites()) {
                int[] counts = census.computeIfAbsent(satellite.kind(), key -> new int[2]);
                counts[1]++;
                if (!filter.showsKind(satellite.kind())) continue;
                boolean found = filter.matches(satellite);
                if (filter.searching() && !found) continue;
                counts[0]++;
                double[] point = ConstellationLayout.position(star, satellite, time);
                add(new Node(device.getId() + '/' + satellite.id(), star, satellite, point[0], point[1],
                        camera.screenX(point[0]), camera.screenY(point[1]),
                        Math.max(1.4, ConstellationLayout.satelliteRadius(satellite.kind()) * zoom), filter.searching()), group);
            }
            groups.put(device.getId(), group);
        }
    }

    private void add(Node node, List<Node> group) {
        nodes.add(node);
        byId.put(node.id(), node);
        group.add(node);
    }

    // --- L'espace ---------------------------------------------------------------------------------

    /**
     * La poussière : des grains posés sur un réseau lâche du monde, qui dérivent au hasard. Une
     * nébuleuse fixe la rend plus ou moins dense, sans jamais laisser de trou noir : mesuré sur une
     * capture, les vides francs et le voile des concentrateurs faisaient des taches noires au zoom.
     */
    private void drawDust(Camera camera, double width, double height) {
        double level = Math.log(DUST_PX / (DUST_STEP * camera.zoom())) / Math.log(2);
        double spacing = DUST_STEP * Math.pow(2, Math.ceil(level));
        double fine = Math.ceil(level) - level;
        int budget = dustLayer(camera, width, height, spacing, 0, 1, MAX_DUST);
        if (fine > .04) dustLayer(camera, width, height, spacing, .5, fine, budget);
    }

    private int dustLayer(Camera camera, double width, double height, double spacing, double offset,
                          double opacity, int budget) {
        double margin = spacing * 3;
        long firstX = (long) Math.floor((camera.worldX(0) - margin) / spacing);
        long lastX = (long) Math.ceil((camera.worldX(width) + margin) / spacing);
        long firstY = (long) Math.floor((camera.worldY(0) - margin) / spacing);
        long lastY = (long) Math.ceil((camera.worldY(height) + margin) / spacing);
        for (long i = firstX; i <= lastX && budget > 0; i++) {
            for (long j = firstY; j <= lastY && budget > 0; j++) {
                long seed = mix(i * 91_381L + j * 7_919L + (offset > 0 ? 17 : 0));
                double driftX = (noise(unit(seed, 0) * 50, time * (.05 + .08 * unit(seed, 10))) - .5) * 1.6;
                double driftY = (noise(unit(seed, 20) * 50 + 17, time * (.05 + .08 * unit(seed, 30))) - .5) * 1.6;
                double x = (i + offset + (unit(seed, 40) - .5) * .6 + driftX) * spacing;
                double y = (j + offset + (unit(seed, 50) - .5) * .6 + driftY) * spacing;
                double nebula = noise(x / 90_000 + 3.1, y / 90_000 - 1.7) * .7 + noise(x / 28_000, y / 28_000 + 9.2) * .3;
                double density = smooth(.28, .78, nebula);
                if (unit(seed, 70) > .5 + .5 * density) continue;
                double sx = camera.screenX(x), sy = camera.screenY(y);
                if (sx < -20 || sx > width + 20 || sy < -20 || sy > height + 20) continue;
                double twinkle = .6 + .4 * Math.sin(time * (.8 + 2 * unit(seed, 60)) + unit(seed, 0) * TAU);
                double alpha = (.3 + .32 * density) * twinkle * opacity;
                space.add(sx, sy, 1.3, unit(seed, 80) > .8 ? P_CYAN : P_DUST, alpha);
                budget--;
            }
        }
        return budget;
    }

    /**
     * Les tracés d'orbite, en fins pointillés de particules, tirés depuis la position vivante de
     * l'hôte : l'ellipse de chaque appareil qui gravite autour d'un autre, et le cercle de chaque
     * anneau du cortège. L'orbite d'un appareil Bluetooth est plus marquée, dans sa couleur.
     */
    private void drawOrbits(Constellation map, Camera camera, double width, double height) {
        double zoom = camera.zoom();
        for (Star star : map.stars().values()) {
            Node self = byId.get(star.device().getId());
            for (double ring : star.rings()) {
                dottedOrbit(self.x(), self.y(), ring * zoom, 1, P_DUST, .2, width, height);
            }
            if (star.parent() == null) continue;
            Node host = byId.get(star.parent().device().getId());
            if (host == null) continue;
            // Le rayon d'avant l'aplatissement : l'ellipse exacte sur laquelle l'appareil se déplace
            double rx = Math.hypot(star.homeX(), star.homeY() / 0.74) * zoom;
            boolean bluetooth = star.device().getType() == DeviceType.BLUETOOTH;
            dottedOrbit(host.x(), host.y(), rx, .74, bluetooth ? P_CYAN : P_DUST, bluetooth ? .3 : .18, width, height);
        }
    }

    private void dottedOrbit(double cx, double cy, double rx, double squash, int colour, double alpha,
                             double width, double height) {
        double ry = rx * squash;
        if (rx < 12 || cx + rx < 0 || cx - rx > width || cy + ry < 0 || cy - ry > height) return;
        int dots = (int) Math.max(24, Math.min(2_400, rx * TAU / 7));
        double sweep = time * .02;
        for (int k = 0; k < dots; k++) {
            double a = k * TAU / dots + sweep;
            double x = cx + Math.cos(a) * rx, y = cy + Math.sin(a) * ry;
            if (x < -4 || x > width + 4 || y < -4 || y > height + 4) continue;
            space.add(x, y, k % 10 == 0 ? 1.6 : 1, colour, k % 10 == 0 ? alpha * 2 : alpha);
        }
    }

    // --- Les liens --------------------------------------------------------------------------------

    /**
     * Le trait qui relie deux systèmes : un seul trait fin, parcouru par un signal. Il suit le
     * modèle — deux systèmes qu'il relie communiquent réellement — et s'arrête au bord des deux
     * sphères.
     */
    private void drawSystemLinks(GraphicsContext gc, Constellation map, double width, double height) {
        for (NetworkLink link : map.links()) {
            if (link.source() == null || link.target() == null) continue;
            Node from = byId.get(link.source().getId());
            Node to = byId.get(link.target().getId());
            if (from == null || to == null || from == to
                    || !from.star().isSystemRoot() || !to.star().isSystemRoot()) continue;
            double fade = 1 - smooth(Math.min(width, height) * .08, Math.min(width, height) * .5,
                    Math.max(offScreen(from.x(), from.y(), width, height), offScreen(to.x(), to.y(), width, height)));
            if (fade < .02) continue;
            signalLine(gc, from, to, WHITE, .42 * fade, P_CYAN,
                    link.source().getId().hashCode() ^ link.target().getId().hashCode(), .22);
        }
    }

    /**
     * La constellation des appareils : deux appareils proches se relient, et le trait s'allume et
     * s'éteint à son rythme. Un hôte n'est jamais relié à ce qui gravite autour de lui : l'orbite
     * dit déjà l'appartenance.
     */
    private void drawDeviceLinks(GraphicsContext gc, Constellation map, double width, double height) {
        List<Node> devices = nodes.stream().filter(Node::device).toList();
        for (int i = 0; i < devices.size(); i++) {
            Node a = devices.get(i);
            for (int j = i + 1; j < devices.size(); j++) {
                Node b = devices.get(j);
                if (a.id().equals(b.star().parentId()) || b.id().equals(a.star().parentId())) continue;
                if (!onScreen(a.x(), a.y(), 0, width, height) && !onScreen(b.x(), b.y(), 0, width, height)) continue;
                double strength = ConstellationLayout.deviceLinkStrength(
                        Math.hypot(a.worldX() - b.worldX(), a.worldY() - b.worldY()));
                if (strength <= .02) continue;
                int seed = a.id().hashCode() ^ b.id().hashCode();
                double gate = smooth(.3, .75, .5 + .5 * Math.sin(time * .14 + Math.floorMod(seed, 6283) / 1000.0));
                double alpha = (.05 + .3 * gate) * strength;
                if (alpha < .02) continue;
                signalLine(gc, a, b, BLUE, alpha, P_CYAN, seed, .18);
            }
        }
    }

    /**
     * Le réseau de neurones du cortège : chaque objet peut se lier à ses deux plus proches voisins,
     * appareil compris, par un trait fin qui s'allume et s'éteint à son rythme. Un lien bien allumé
     * transporte un signal. Ces liens sont décoratifs : ils ne mesurent aucun échange.
     */
    private void drawNeighbourLinks(GraphicsContext gc, double width, double height) {
        double fade = 1 - Math.exp(-frameDt / .7);
        Map<String, Double> wanted = new HashMap<>();
        for (List<Node> group : groups.values()) {
            Node centre = group.get(0);
            double reach = centre.star().systemRadius() * centre.radius() / Math.max(1e-9, centre.star().coreRadius());
            if (group.size() < 2 || reach < 30 || !onScreen(centre.x(), centre.y(), reach, width, height)) continue;
            for (int i = 1; i < group.size(); i++) {
                Node p = group.get(i);
                int first = -1, second = -1;
                double best = Double.POSITIVE_INFINITY, next = Double.POSITIVE_INFINITY;
                for (int j = 0; j < group.size(); j++) {
                    if (j == i) continue;
                    Node q = group.get(j);
                    double d = Math.hypot(p.worldX() - q.worldX(), p.worldY() - q.worldY());
                    if (d < best) {
                        next = best;
                        second = first;
                        best = d;
                        first = j;
                    } else if (d < next) {
                        next = d;
                        second = j;
                    }
                }
                for (int j : new int[] { first, second }) {
                    if (j < 0) continue;
                    Node q = group.get(j);
                    String key = p.id().compareTo(q.id()) < 0 ? p.id() + '>' + q.id() : q.id() + '>' + p.id();
                    double phase = Math.floorMod(key.hashCode(), 6283) / 1000.0;
                    wanted.merge(key, smooth(.35, .75, .5 + .5 * Math.sin(time * .22 + phase)), Math::max);
                }
            }
        }
        for (String key : wanted.keySet()) linkFade.putIfAbsent(key, 0.0);
        linkFade.replaceAll((key, alpha) -> alpha + (wanted.getOrDefault(key, 0.0) - alpha) * fade);
        linkFade.values().removeIf(alpha -> alpha < .01);

        for (Map.Entry<String, Double> link : linkFade.entrySet()) {
            String key = link.getKey();
            int cut = key.indexOf('>');
            Node from = byId.get(key.substring(0, cut));
            Node to = byId.get(key.substring(cut + 1));
            if (from == null || to == null) continue;
            if (!onScreen(from.x(), from.y(), 0, width, height) && !onScreen(to.x(), to.y(), 0, width, height)) continue;
            Node coloured = from.device() ? to : from;
            int colour = coloured.device() ? P_BLUE : paletteOf(coloured.satellite().kind());
            double value = link.getValue();
            if (value > .55) {
                signalLine(gc, from, to, PALETTE[colour], value * .38, colour, key.hashCode(), .3);
            } else {
                line(gc, from, to, PALETTE[colour], value * .38);
            }
        }
    }

    private void line(GraphicsContext gc, Node from, Node to, Color colour, double alpha) {
        double[] segment = ConstellationLayout.clipBetween(from.x(), from.y(), edge(from), to.x(), to.y(), edge(to));
        if (segment == null || alpha < .02) return;
        gc.setLineWidth(.6);
        gc.setStroke(colour.deriveColor(0, 1, 1, alpha));
        gc.strokeLine(segment[0], segment[1], segment[2], segment[3]);
    }

    /** Un seul trait fin, d'opacité constante, parcouru par un point lumineux. */
    private void signalLine(GraphicsContext gc, Node from, Node to, Color colour, double alpha, int particle,
                            int seed, double speed) {
        double[] segment = ConstellationLayout.clipBetween(from.x(), from.y(), edge(from), to.x(), to.y(), edge(to));
        if (segment == null || alpha < .02) return;
        gc.setLineWidth(.7);
        gc.setStroke(colour.deriveColor(0, 1, 1, alpha));
        gc.strokeLine(segment[0], segment[1], segment[2], segment[3]);
        double u = (time * speed + Math.floorMod(seed, 1000) / 1000.0) % 1;
        double t = (seed & 1) == 0 ? u : 1 - u;
        matter.add(segment[0] + (segment[2] - segment[0]) * t, segment[1] + (segment[3] - segment[1]) * t,
                2.2, particle, Math.min(1, alpha * 2.4) * Math.sin(Math.PI * u));
    }

    /** Où un trait s'arrête : au bord visible de l'objet, piques comprises. */
    private static double edge(Node node) {
        return node.radius() * (node.device() ? 1.35 : 1.15) + 3;
    }

    // --- Les objets -------------------------------------------------------------------------------

    private void drawObjects(GraphicsContext gc, double width, double height) {
        for (Node node : nodes) {
            if (!node.device() && onScreen(node.x(), node.y(), node.radius() * 2 + 12, width, height)) {
                satelliteObject(gc, node);
            }
        }
        for (Node node : nodes) {
            if (node.device() && onScreen(node.x(), node.y(), node.radius() * 2 + 12, width, height)) {
                deviceObject(gc, node);
            }
        }
    }

    /**
     * Un appareil : ses couches s'empilent selon ce que l'on sait de lui, comme les étapes d'une
     * planche d'assemblage.
     */
    private void deviceObject(GraphicsContext gc, Node node) {
        Device device = node.star().device();
        double cx = node.x(), cy = node.y(), r = node.radius();
        boolean dormant = device.getPresence() == Presence.DORMANT;
        double alpha = dormant ? .42 : 1;
        long seed = device.getId().hashCode();
        double yaw = time * (dormant ? .03 : .1) + (seed & 0xFF) * .03;
        double tilt = .4 + (seed >>> 8 & 0xFF) / 255.0 * .5;
        int colour = deviceColour(device.getType());
        if (r < 3) {
            matter.add(cx, cy, 2.4, colour, alpha);
            matter.add(cx + 2, cy - 1, 1, colour, alpha * .5);
            return;
        }
        if (r < 7) {
            cloud(cx, cy, r, yaw, tilt, colour, alpha);
            return;
        }
        int ports = device.getEndpoints().size();
        int orbiting = carried.getOrDefault(device.getId(), 0);
        boolean named = device.getName() != null && !device.getName().startsWith("Appareil");
        switch (device.getType()) {
            case GATEWAY_ROUTER -> {
                circles(gc, cx, cy, r, yaw, tilt, WHITE, alpha * .55);
                cage(gc, cx, cy, r, yaw, tilt, TEAL, alpha, true, true);
                if (ports > 0) molecules(cx, cy, r, yaw, tilt, Math.min(12, ports), alpha, seed);
                nucleus(gc, cx, cy, r, alpha, seed);
                spikes(gc, cx, cy, r, yaw, tilt, Math.max(12, Math.min(96, 12 + 6 * orbiting)), 1, .55,
                        WHITE, P_CYAN, alpha, seed);
            }
            case LOCAL_HOST -> {
                shell(gc, cx, cy, r, yaw, tilt, alpha);
                circles(gc, cx, cy, r, yaw, tilt, WHITE, alpha * .5);
                cage(gc, cx, cy, r, yaw, tilt, TEAL, alpha * .8, true, true);
                if (ports > 0) molecules(cx, cy, r, yaw, tilt, Math.max(1, Math.min(12, ports / 3)), alpha, seed);
                nucleus(gc, cx, cy, r, alpha, seed);
                int spikes = 10 + 4 * device.getAdapters().size() + 6 * orbiting;
                spikes(gc, cx, cy, r, yaw, tilt, Math.min(96, spikes), 1, .45, WHITE, P_CYAN, alpha, seed);
            }
            case LAN_PEER -> {
                shell(gc, cx, cy, r, yaw, tilt, alpha);
                circles(gc, cx, cy, r, yaw, tilt, WHITE, alpha * .5);
                if (ports == 0 && !named) {
                    cage(gc, cx, cy, r, yaw, tilt, BLUE, alpha, false, true);
                }
                if (ports > 0) molecules(cx, cy, r, yaw, tilt, Math.min(12, ports), alpha, seed);
                if (named) nucleus(gc, cx, cy, r, alpha, seed);
                if (ports > 0 || named) {
                    spikes(gc, cx, cy, r, yaw, tilt, Math.min(60, 8 + 4 * ports), 1, .4, WHITE, P_CYAN, alpha, seed);
                }
            }
            case REMOTE_SERVER -> {
                wire(gc, cx, cy, r, yaw, tilt, alpha);
                cage(gc, cx, cy, r, yaw, tilt, WHITE, alpha * .8, true, true);
            }
            case BLUETOOTH -> beacon(gc, cx, cy, r, yaw, tilt, dormant, seed);
        }
    }

    /** Un objet du cortège : la même famille de formes, en petit. */
    private void satelliteObject(GraphicsContext gc, Node node) {
        SatelliteKind kind = node.satellite().kind();
        double cx = node.x(), cy = node.y(), r = node.radius();
        long seed = node.id().hashCode();
        double yaw = time * .16 + (seed & 0xFF) * .05;
        double tilt = .35 + (seed >>> 8 & 0xFF) / 255.0 * .6;
        int colour = paletteOf(kind);
        if (r < 2.5) {
            matter.add(cx, cy, 2, colour, .9);
            return;
        }
        if (r < 6) {
            cloud(cx, cy, r, yaw, tilt, colour, .9);
            return;
        }
        switch (kind) {
            case ADAPTER -> {
                shell(gc, cx, cy, r, yaw, tilt, 1);
                circles(gc, cx, cy, r, yaw, tilt, WHITE, .5);
                cage(gc, cx, cy, r, yaw, tilt, BLUE, 1, false, true);
            }
            case PERIPHERAL -> {
                wire(gc, cx, cy, r, yaw, tilt, 1);
                cage(gc, cx, cy, r, yaw, tilt, WHITE, .7, true, r >= 12);
            }
            case PORT -> {
                dottedSphere(cx, cy, r, yaw, tilt, P_GOLD, 1);
                if (r >= 14) molecules(cx, cy, r, yaw, tilt, 6, 1, seed);
                nucleus(gc, cx, cy, r, 1, seed);
            }
            case SERVICE -> spikes(gc, cx, cy, r, yaw, tilt, 22, .18, .85, CYAN, P_CYAN, 1, seed);
        }
    }

    /** Trop petit pour lire des couches : une sphère de points, de la couleur de l'objet. */
    private void cloud(double cx, double cy, double r, double yaw, double tilt, int colour, double alpha) {
        double[][] sphere = spherePoints(48);
        for (double[] p : sphere) {
            rotate(p, yaw, tilt);
            double front = (turned[2] + 1) / 2;
            matter.add(cx + turned[0] * r, cy + turned[1] * r, front > .7 ? 1.3 : 1, colour, alpha * (.2 + .75 * front));
        }
    }

    /**
     * La sphère bleue translucide : un volume dégradé, un semis de particules sur sa surface, et de
     * près un réseau de traits très fins entre voisins, piqué de nœuds cyan.
     */
    private void shell(GraphicsContext gc, double cx, double cy, double r, double yaw, double tilt, double alpha) {
        gc.setFill(new RadialGradient(0, 0, cx - r * .3, cy - r * .35, r * 1.35, false, CycleMethod.NO_CYCLE,
                new Stop(0, BLUE.interpolate(WHITE, .3).deriveColor(0, 1, 1, alpha * .42)),
                new Stop(.55, BLUE.deriveColor(0, 1, 1, alpha * .2)),
                new Stop(1, BLUE.deriveColor(0, 1, .55, alpha * .1))));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);
        gc.setLineWidth(.8);
        gc.setStroke(BLUE.interpolate(WHITE, .35).deriveColor(0, 1, 1, alpha * .6));
        gc.strokeOval(cx - r, cy - r, r * 2, r * 2);

        int count = bucket((int) Math.max(48, Math.min(768, r * r * .3)));
        double[][] sphere = spherePoints(count);
        for (double[] p : sphere) {
            rotate(p, yaw, tilt);
            double front = (turned[2] + 1) / 2;
            matter.add(cx + turned[0] * r, cy + turned[1] * r, 1, P_CYAN, alpha * (.05 + .3 * front));
        }
        if (r < 16) return;
        int knots = bucket((int) Math.max(48, Math.min(192, r * 1.4)));
        double[][] points = spherePoints(knots);
        int[] pairs = plexus(knots);
        double[] px = new double[knots], py = new double[knots], pz = new double[knots];
        for (int i = 0; i < knots; i++) {
            rotate(points[i], yaw * .8, tilt);
            px[i] = cx + turned[0] * r;
            py[i] = cy + turned[1] * r;
            pz[i] = turned[2];
        }
        gc.setLineWidth(.5);
        gc.setStroke(CYAN.deriveColor(0, 1, 1, alpha * .2));
        gc.beginPath();
        for (int e = 0; e < pairs.length; e += 2) {
            int a = pairs[e], b = pairs[e + 1];
            if (pz[a] + pz[b] < 0) continue;
            gc.moveTo(px[a], py[a]);
            gc.lineTo(px[b], py[b]);
        }
        gc.stroke();
        for (int i = 0; i < knots; i++) {
            if (pz[i] > .15) matter.add(px[i], py[i], r > 40 ? 2 : 1.5, P_CYAN, alpha * (.35 + .6 * pz[i]));
        }
    }

    /** Les quatre grands cercles posés sur la sphère, pâles derrière, nets devant. */
    private void circles(GraphicsContext gc, double cx, double cy, double r, double yaw, double tilt,
                         Color colour, double alpha) {
        gc.setLineWidth(r > 40 ? .9 : .7);
        for (double[][] circle : CIRCLES) {
            for (int k = 0; k < circle.length; k++) {
                rotate(circle[k], yaw, tilt);
                ringX[k] = cx + turned[0] * r;
                ringY[k] = cy + turned[1] * r;
                ringZ[k] = turned[2];
            }
            for (int pass = 0; pass < 2; pass++) {
                boolean front = pass == 1;
                gc.setStroke(colour.deriveColor(0, 1, 1, alpha * (front ? .8 : .18)));
                gc.beginPath();
                for (int k = 1; k < circle.length; k++) {
                    if ((ringZ[k] + ringZ[k - 1] >= 0) != front) continue;
                    gc.moveTo(ringX[k - 1], ringY[k - 1]);
                    gc.lineTo(ringX[k], ringY[k]);
                }
                gc.stroke();
            }
        }
    }

    /**
     * La cage : un dodécaèdre autour de la sphère, et une bulle à chacun des douze sommets de
     * l'icosaèdre, qui tournent avec elle.
     */
    private void cage(GraphicsContext gc, double cx, double cy, double r, double yaw, double tilt, Color colour,
                      double alpha, boolean lines, boolean bubbles) {
        if (lines) {
            for (int i = 0; i < DODECA.length; i++) {
                rotate(DODECA[i], yaw, tilt);
                meshX[i] = cx + turned[0] * r * 1.12;
                meshY[i] = cy + turned[1] * r * 1.12;
                meshZ[i] = turned[2];
            }
            gc.setLineWidth(r > 40 ? .9 : .7);
            for (int pass = 0; pass < 2; pass++) {
                boolean front = pass == 1;
                gc.setStroke(colour.deriveColor(0, 1, 1, alpha * (front ? .75 : .18)));
                gc.beginPath();
                for (int e = 0; e < DODECA_EDGES.length; e += 2) {
                    int a = DODECA_EDGES[e], b = DODECA_EDGES[e + 1];
                    if ((meshZ[a] + meshZ[b] >= 0) != front) continue;
                    gc.moveTo(meshX[a], meshY[a]);
                    gc.lineTo(meshX[b], meshY[b]);
                }
                gc.stroke();
            }
            int dot = colour == WHITE ? P_WHITE : colour == BLUE ? P_BLUE : P_TEAL;
            for (int i = 0; i < DODECA.length; i++) {
                if (meshZ[i] > 0) matter.add(meshX[i], meshY[i], 1.5, dot, alpha * (.4 + .6 * meshZ[i]));
            }
        }
        if (!bubbles) return;
        gc.setLineWidth(.7);
        for (double[] vertex : ICOSA) {
            rotate(vertex, yaw, tilt);
            double z = turned[2], front = (z + 1) / 2;
            double bx = cx + turned[0] * r * 1.2, by = cy + turned[1] * r * 1.2;
            double br = r * .13 * (1 + .15 * z);
            if (br < 1.6) {
                matter.add(bx, by, 1.6, P_WHITE, alpha * (.3 + .6 * front));
                continue;
            }
            gc.setFill(colour.deriveColor(0, 1, 1, alpha * .08 * front));
            gc.fillOval(bx - br, by - br, br * 2, br * 2);
            gc.setStroke(colour.interpolate(WHITE, .3).deriveColor(0, 1, 1, alpha * (.15 + .55 * front)));
            gc.strokeOval(bx - br, by - br, br * 2, br * 2);
            matter.add(bx - br * .35, by - br * .35, Math.max(1.2, br * .3), P_WHITE, alpha * .7 * front);
        }
    }

    /** La sphère de fil : une géodésique blanche, arêtes de devant nettes, de derrière pâles. */
    private void wire(GraphicsContext gc, double cx, double cy, double r, double yaw, double tilt, double alpha) {
        for (int i = 0; i < GEODESIC.length; i++) {
            rotate(GEODESIC[i], yaw, tilt);
            meshX[i] = cx + turned[0] * r;
            meshY[i] = cy + turned[1] * r;
            meshZ[i] = turned[2];
        }
        gc.setLineWidth(.55);
        for (int pass = 0; pass < 2; pass++) {
            boolean front = pass == 1;
            gc.setStroke(WHITE.deriveColor(0, 1, 1, alpha * (front ? .6 : .12)));
            gc.beginPath();
            for (int e = 0; e < GEODESIC_EDGES.length; e += 2) {
                int a = GEODESIC_EDGES[e], b = GEODESIC_EDGES[e + 1];
                if ((meshZ[a] + meshZ[b] >= 0) != front) continue;
                gc.moveTo(meshX[a], meshY[a]);
                gc.lineTo(meshX[b], meshY[b]);
            }
            gc.stroke();
        }
        for (int i = 0; i < GEODESIC.length; i++) {
            if (meshZ[i] > 0) matter.add(meshX[i], meshY[i], 1.4, P_WHITE, alpha * (.3 + .7 * meshZ[i]));
        }
    }

    /** La sphère d'or pointillée : les arêtes d'une géodésique, tracées en particules. */
    private void dottedSphere(double cx, double cy, double r, double yaw, double tilt, int colour, double alpha) {
        for (int i = 0; i < GEODESIC.length; i++) {
            rotate(GEODESIC[i], yaw, tilt);
            meshX[i] = cx + turned[0] * r;
            meshY[i] = cy + turned[1] * r;
            meshZ[i] = turned[2];
        }
        int steps = r > 40 ? 6 : r > 16 ? 4 : 2;
        for (int e = 0; e < GEODESIC_EDGES.length; e += 2) {
            int a = GEODESIC_EDGES[e], b = GEODESIC_EDGES[e + 1];
            for (int s = 0; s < steps; s++) {
                double t = s / (double) steps;
                double z = meshZ[a] + (meshZ[b] - meshZ[a]) * t, front = (z + 1) / 2;
                matter.add(meshX[a] + (meshX[b] - meshX[a]) * t, meshY[a] + (meshY[b] - meshY[a]) * t,
                        s == 0 && front > .6 ? 1.5 : 1, colour, alpha * (.1 + .65 * front));
            }
        }
    }

    /** Les amas d'or : de petites molécules qui tournent à l'intérieur de la sphère. */
    private void molecules(double cx, double cy, double r, double yaw, double tilt, int count, double alpha, long seed) {
        double[][] places = spherePoints(Math.max(1, count));
        for (int i = 0; i < count; i++) {
            rotate(places[i], yaw * .6, tilt);
            double depth = (turned[2] + 1) / 2;
            double mx = cx + turned[0] * r * .52, my = cy + turned[1] * r * .52;
            double mr = r * .085;
            if (mr < 1.6) {
                matter.add(mx, my, 1.8, P_GOLD, alpha * (.45 + .55 * depth));
                continue;
            }
            double spin = time * .9 + i + (seed & 0xF);
            for (double[] atom : ICOSA) {
                rotate(atom, spin, .6);
                double shade = (turned[2] + 1) / 2;
                matter.add(mx + turned[0] * mr, my + turned[1] * mr, mr > 4 ? 1.8 : 1.3, P_GOLD,
                        alpha * (.5 + .5 * depth) * (.55 + .45 * shade));
            }
            matter.add(mx, my, mr > 4 ? 2.2 : 1.6, P_GOLD, alpha * (.5 + .5 * depth));
        }
    }

    /** Le noyau vert : un amas dense de particules qui frémit au centre, cerclé de près. */
    private void nucleus(GraphicsContext gc, double cx, double cy, double r, double alpha, long seed) {
        double nr = r * .16;
        int count = (int) Math.max(10, Math.min(120, nr * nr * 1.2));
        for (int k = 0; k < count; k++) {
            long h = mix(seed * 7_919L + k);
            double a = unit(h, 0) * TAU + time * (.2 + .4 * unit(h, 10));
            double d = nr * Math.sqrt(unit(h, 20)) * (1 + .08 * Math.sin(time * 2 + k));
            matter.add(cx + Math.cos(a) * d, cy + Math.sin(a) * d, nr > 6 ? 1.5 : 1.2, P_GREEN, alpha * (.45 + .55 * unit(h, 30)));
        }
        if (nr > 5) {
            gc.setLineWidth(.7);
            gc.setStroke(GREEN.deriveColor(0, 1, 1, alpha * .45));
            gc.strokeOval(cx - nr * 1.4, cy - nr * 1.4, nr * 2.8, nr * 2.8);
        }
    }

    /**
     * Les piques : de fins traits qui partent de la sphère dans toutes les directions, chacun
     * terminé par une perle. Vus en perspective, ceux qui pointent vers nous paraissent courts.
     */
    private void spikes(GraphicsContext gc, double cx, double cy, double r, double yaw, double tilt, int count,
                        double inner, double length, Color line, int tip, double alpha, long seed) {
        double[][] directions = spherePoints(count);
        gc.setLineWidth(.6);
        gc.setStroke(line.deriveColor(0, 1, 1, alpha * .45));
        gc.beginPath();
        for (int i = 0; i < count; i++) {
            long h = mix(seed * 17L + i);
            rotate(directions[i], yaw * .8, tilt);
            if (turned[2] < -.55) continue;
            double front = (turned[2] + 1) / 2;
            double extent = length * (.45 + .55 * unit(h, 0)) * (.92 + .08 * Math.sin(time * (.8 + unit(h, 10)) + i));
            double x0 = cx + turned[0] * r * inner, y0 = cy + turned[1] * r * inner;
            double x1 = cx + turned[0] * r * (inner + extent), y1 = cy + turned[1] * r * (inner + extent);
            gc.moveTo(x0, y0);
            gc.lineTo(x1, y1);
            matter.add(x1, y1, r > 20 ? 2.2 : 1.6, tip, alpha * (.35 + .65 * front));
            if (unit(h, 20) > .6) {
                matter.add(x0 + (x1 - x0) * .6, y0 + (y1 - y0) * .6, 1.2, tip, alpha * .5 * front);
            }
        }
        gc.stroke();
    }

    /**
     * Un appareil Bluetooth : une balise. Un cœur cyan lumineux, trois anneaux inclinés qui
     * tournent autour comme un gyroscope, chacun portant un point, et des ondes de particules qui
     * partent du cœur et s'effacent en s'élargissant. Éteint, il ne rayonne plus : ses anneaux
     * s'arrêtent presque et ses ondes disparaissent.
     */
    private void beacon(GraphicsContext gc, double cx, double cy, double r, double yaw, double tilt,
                        boolean dormant, long seed) {
        double alpha = dormant ? .45 : 1;
        double core = r * .42;
        gc.setFill(new RadialGradient(0, 0, cx, cy, core * 1.9, false, CycleMethod.NO_CYCLE,
                new Stop(0, CYAN.interpolate(WHITE, .5).deriveColor(0, 1, 1, alpha * .95)),
                new Stop(.35, CYAN.deriveColor(0, 1, 1, alpha * .55)),
                new Stop(1, CYAN.deriveColor(0, 1, 1, 0))));
        gc.fillOval(cx - core * 1.9, cy - core * 1.9, core * 3.8, core * 3.8);
        cloud(cx, cy, core, yaw * 2, tilt, P_WHITE, alpha);

        // Trois anneaux, chacun sur son propre plan : leurs normales sont écartées de 60°
        gc.setLineWidth(r > 30 ? 1.2 : .9);
        double[] point = new double[3];
        for (int k = 0; k < 3; k++) {
            double plane = k * Math.PI / 3 + .35;
            double planeYaw = yaw * (1 + k * .6) + k * 2.1;
            int steps = 48;
            double previousX = 0, previousY = 0, previousZ = 0;
            for (int i = 0; i <= steps; i++) {
                double a = i * TAU / steps;
                point[0] = Math.cos(a);
                point[1] = Math.sin(a) * Math.sin(plane);
                point[2] = Math.sin(a) * Math.cos(plane);
                rotate(point, planeYaw, tilt);
                double x = cx + turned[0] * r, y = cy + turned[1] * r, z = turned[2];
                if (i > 0) {
                    gc.setStroke(CYAN.deriveColor(0, 1, 1, alpha * (z + previousZ >= 0 ? .8 : .22)));
                    gc.strokeLine(previousX, previousY, x, y);
                }
                previousX = x;
                previousY = y;
                previousZ = z;
            }
            double orbit = time * (dormant ? .1 : .9 + .4 * k) + k;
            point[0] = Math.cos(orbit);
            point[1] = Math.sin(orbit) * Math.sin(plane);
            point[2] = Math.sin(orbit) * Math.cos(plane);
            rotate(point, planeYaw, tilt);
            matter.add(cx + turned[0] * r, cy + turned[1] * r, r > 20 ? 3 : 2.2, P_WHITE, alpha * (.5 + .5 * (turned[2] + 1) / 2));
        }
        if (dormant) return;

        // Les ondes : trois anneaux de particules qui naissent au cœur et meurent à 2,6 rayons
        for (int w = 0; w < 3; w++) {
            double p = (time * .45 + w / 3.0 + (seed & 0xFF) / 255.0) % 1;
            double radius = r * (.5 + 2.1 * p);
            int dots = (int) Math.max(24, Math.min(160, radius * .9));
            double fade = Math.pow(1 - p, 1.6);
            for (int i = 0; i < dots; i++) {
                double a = i * TAU / dots + w;
                matter.add(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius * .9, r > 20 ? 2 : 1.6, P_CYAN, Math.min(1, 1.3 * fade));
            }
        }
    }

    /** La sélection et la recherche : quatre équerres fines autour de l'objet. */
    private void drawSelection(GraphicsContext gc, String selectedId) {
        for (Node node : nodes) {
            boolean chosen = node.id().equals(selectedId);
            if (!chosen && !node.match()) continue;
            gc.setStroke(chosen ? TEXT : ACCENT);
            gc.setLineWidth(1);
            double r = Math.max(6, node.radius() * (node.device() ? 1.5 : 1.3)) + 6;
            for (int sx : new int[] { -1, 1 }) {
                for (int sy : new int[] { -1, 1 }) {
                    double x = node.x() + sx * r, y = node.y() + sy * r;
                    gc.strokeLine(x, y, x - sx * 7, y);
                    gc.strokeLine(x, y, x, y - sy * 7);
                }
            }
        }
    }

    // --- Les textes -------------------------------------------------------------------------------

    /**
     * Les noms sont des légendes : un trait fin qui part de l'objet en oblique, un petit anneau, le
     * nom en capitales et l'adresse dessous. Seuls les concentrateurs gardent le leur en
     * permanence ; les autres apparaissent au survol, à la sélection ou quand une recherche les
     * désigne.
     */
    private void drawLabels(GraphicsContext gc, Constellation map, String selectedId, String hoveredId,
                            Filter filter, double width, double height) {
        labels.clear();
        labels.add(new Box(0, 0, 440, 210));
        labels.add(new Box(width - 330, 40, 330, panelBottom - 40));
        List<Node> ordered = nodes.stream().filter(Node::device)
                .sorted(Comparator.comparingDouble((Node n) ->
                        (n.star().isSystemRoot() ? 1e9 : 0) + n.star().coreRadius()).reversed())
                .toList();
        for (Node node : ordered) {
            Star star = node.star();
            String id = node.id();
            boolean named = star.isSystemRoot() || id.equals(selectedId) || id.equals(hoveredId) || node.match();
            if (!named || !onScreen(node.x(), node.y(), 0, width, height)) continue;
            double r = Math.max(5, node.radius() * 1.45);
            Device device = star.device();
            String name = device.getName() == null ? text("APPAREIL", "DEVICE")
                    : localizedDetail(device.getName()).toUpperCase(Locale.ROOT);
            String detail = typeName(device.getType()) + "   ·   " + deviceSummary(device);
            double w = Math.max(name.length() * 7.4, detail.length() * 5.6) + 12;
            // La légende part à droite ; si la place manque, elle part à gauche, en miroir, et son
            // texte s'aligne alors sur le trait
            double startY = node.y() - r * .72 - 2, elbowY = startY - 16;
            double side = 0;
            Box box = null;
            for (double candidate : new double[] { 1, -1 }) {
                double start = node.x() + candidate * (r * .72 + 2);
                double end = start + candidate * 50;
                double left = candidate > 0 ? start : end - 9 - w;
                double right = candidate > 0 ? end + 9 + w : start;
                Box tried = new Box(left - 2, elbowY - 16, right - left + 4, 38);
                if (tried.x() >= 12 && tried.x() + tried.width() <= width - 12 && tried.y() >= 12 && elbowY <= height - 90
                        && labels.stream().noneMatch(tried::hits)) {
                    box = tried;
                    side = candidate;
                    break;
                }
            }
            if (box == null) continue;
            labels.add(box);
            double startX = node.x() + side * (r * .72 + 2);
            double elbowX = startX + side * 16, endX = startX + side * 50, textX = endX + side * 9;
            gc.setGlobalAlpha(device.getPresence() == Presence.DORMANT ? .6 : 1);
            gc.setStroke(MUTED.deriveColor(0, 1, 1, .7));
            gc.setLineWidth(.8);
            gc.strokeLine(startX, startY, elbowX, elbowY);
            gc.strokeLine(elbowX, elbowY, endX, elbowY);
            gc.setStroke(TEXT);
            gc.strokeOval(endX - 2.5, elbowY - 2.5, 5, 5);
            gc.setFill(INK.deriveColor(0, 1, 1, .6));
            gc.fillRect(side > 0 ? textX - 4 : textX - w + 4, elbowY - 14, w, 32);
            gc.setTextAlign(side > 0 ? TextAlignment.LEFT : TextAlignment.RIGHT);
            gc.setFill(TEXT);
            gc.setFont(LABEL);
            gc.fillText(name, textX, elbowY + 1);
            gc.setFont(CAPTION);
            gc.setFill(PALETTE[deviceColour(device.getType())].interpolate(MUTED, .35));
            gc.fillText(detail, textX, elbowY + 14);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setGlobalAlpha(1);
    }

    /** Le cartouche, à la manière d'une planche scientifique : série, titre, notice et légendes. */
    private void drawMonitor(GraphicsContext gc, Constellation map, Filter filter, double width) {
        double x = width - 300, y = 70, w = 262;
        gc.setFill(INK.deriveColor(0, 1, 1, .78));
        gc.fillRect(x - 16, y - 22, w + 32, panelBottom - (y - 22));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(MICRO);
        gc.setFill(ACCENT);
        gc.fillText(text("RÉSEAU.SÉRIE", "NETWORK.SERIES"), x, y);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("N. " + map.stars().size(), x + w, y);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setStroke(ACCENT.deriveColor(0, 1, 1, .8));
        gc.setLineWidth(1);
        gc.strokeLine(x, y + 12, x + 18, y + 12);

        gc.setFill(TEXT);
        gc.setFont(DISPLAY);
        gc.fillText("CONSTELLATION", x, y + 44);
        gc.fillText(text("DU RÉSEAU", "OF THE NETWORK"), x, y + 70);

        gc.setFont(CAPTION);
        gc.setFill(MUTED);
        String[] notice = language == UiLanguage.FRENCH
                ? new String[] {
                    "CHAQUE SPHÈRE EST UN APPAREIL, À LA TAILLE DE CE",
                    "QUE L'ON SAIT DE LUI. SES COUCHES SE LISENT :",
                    "CAGE  CONCENTRATEUR   ·   OR  PORTS OUVERTS",
                    "NOYAU VERT  IDENTIFIÉ   ·   PIQUES  LIAISONS.",
                    "LES LIENS ENTRE VOISINS SONT DÉCORATIFS." }
                : new String[] {
                    "EACH SPHERE IS A DEVICE, SIZED BY HOW MUCH",
                    "IS KNOWN ABOUT IT. ITS LAYERS READ AS:",
                    "CAGE  HUB   ·   GOLD  OPEN PORTS",
                    "GREEN CORE  IDENTIFIED   ·   SPIKES  LINKS.",
                    "LINKS BETWEEN NEIGHBORS ARE DECORATIVE." };
        for (int i = 0; i < notice.length; i++) gc.fillText(notice[i], x, y + 96 + i * 14);

        // Le cortège : chaque nature avec sa forme vivante
        double rowsY = y + 184;
        gc.setFont(MICRO);
        gc.setFill(TEXT);
        gc.fillText(text("CORTÈGE", "ORBITAL GROUP"), x, rowsY);
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(text("AFFICHÉS / CONNUS", "SHOWN / KNOWN"), x + w, rowsY);
        gc.setTextAlign(TextAlignment.LEFT);
        SatelliteKind[] kinds = SatelliteKind.values();
        for (int i = 0; i < kinds.length; i++) {
            double rowY = rowsY + 26 + i * 30;
            boolean shown = filter.showsKind(kinds[i]);
            Node sample = new Node("legende:" + kinds[i], null, new Satellite("legende", null, "", "", kinds[i],
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 0, x + 11, rowY - 3, 11, false);
            if (shown) {
                satelliteObject(gc, sample);
            } else {
                matter.add(x + 11, rowY - 3, 2, P_DUST, .5);
            }
            int[] counts = census.getOrDefault(kinds[i], new int[2]);
            gc.setFont(MICRO);
            gc.setFill(shown ? TEXT : MUTED);
            gc.fillText(kindName(kinds[i]), x + 34, rowY);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFont(CAPTION);
            gc.setFill(MUTED);
            gc.fillText(shown ? counts[0] + " / " + counts[1] : text("MASQUÉ", "HIDDEN"), x + w, rowY);
            gc.setTextAlign(TextAlignment.LEFT);
        }
        matter.flush(gc);

        // Les appareils, par nature de liaison. « Réseau local » et non « Wi-Fi » : depuis cette
        // machine, rien ne distingue un client sans fil d'un client câblé derrière la box.
        int network = 0, bluetooth = 0, bluetoothActive = 0;
        for (Star star : map.stars().values()) {
            switch (star.device().getType()) {
                case LAN_PEER, REMOTE_SERVER -> network++;
                case BLUETOOTH -> {
                    bluetooth++;
                    if (star.device().getPresence() == Presence.ACTIVE) bluetoothActive++;
                }
                default -> {
                }
            }
        }
        double censusY = rowsY + 26 + kinds.length * 30 + 6;
        row(gc, x, censusY, w, text("RÉSEAU LOCAL", "LOCAL NETWORK"),
                network + text(" APPAREIL", " DEVICE") + (network > 1 ? "S" : ""));
        row(gc, x, censusY + 16, w, "BLUETOOTH", bluetoothActive + text(" ACTIF / ", " ACTIVE / ") + bluetooth);

        // Le temps réel : latence mesurée vers la passerelle et voisins connus
        Pulse state = pulse;
        double liveY = censusY + 46;
        if (state != null) {
            double breath = .45 + .55 * (.5 + .5 * Math.sin(time * 1.9));
            gc.setFill((state.latencyMs() >= 0 ? ACCENT : MUTED).deriveColor(0, 1, 1, breath));
            gc.fillOval(x, liveY - 7, 6, 6);
            gc.setFont(MICRO);
            gc.setFill(TEXT);
            gc.fillText(text("TEMPS RÉEL", "LIVE"), x + 12, liveY);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFill(MUTED);
            gc.fillText(state.ageMillis() < 0 ? "" : text("IL Y A ", "")
                    + state.ageMillis() / 1000 + text(" S", " S AGO"), x + w, liveY);
            gc.setTextAlign(TextAlignment.LEFT);
            row(gc, x, liveY + 18, w, text("PASSERELLE", "GATEWAY"),
                    state.latencyMs() >= 0 ? state.latencyMs() + " MS" : text("INJOIGNABLE", "UNREACHABLE"));
            row(gc, x, liveY + 34, w, text("VOISINS CONNUS", "KNOWN NEIGHBORS"), Integer.toString(state.neighbours()));
            sparkline(gc, x, liveY + 44, w, 24, state.history());
        }
        if (filter.searching()) {
            gc.setFont(MICRO);
            gc.setFill(ACCENT);
            gc.fillText(text("RECHERCHE « ", "SEARCH “") + filter.query().toUpperCase(Locale.ROOT)
                    + text(" »", "”"), x, panelBottom - 14, w);
        }
    }

    private void row(GraphicsContext gc, double x, double y, double w, String label, String value) {
        gc.setFont(MICRO);
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(label, x, y);
        gc.setFont(CAPTION);
        gc.setFill(TEXT);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(value, x + w, y);
        gc.setTextAlign(TextAlignment.LEFT);
    }

    /** La courbe des dernières mesures, normalisée sur la plus lente d'entre elles. */
    private void sparkline(GraphicsContext gc, double x, double y, double width, double height, double[] history) {
        if (history == null || history.length < 2) return;
        double worst = 1;
        for (double value : history) worst = Math.max(worst, value);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeLine(x, y + height, x + width, y + height);
        gc.setStroke(ACCENT.deriveColor(0, 1, 1, .85));
        gc.setLineWidth(1.1);
        double step = width / (history.length - 1);
        for (int i = 1; i < history.length; i++) {
            double previous = Math.max(0, history[i - 1]);
            double current = Math.max(0, history[i]);
            gc.strokeLine(x + (i - 1) * step, y + height - previous / worst * height,
                    x + i * step, y + height - current / worst * height);
        }
    }

    /**
     * La fiche d'observation d'un objet cliqué : l'objet vivant, agrandi dans une fenêtre avec ce
     * qui l'entoure, puis ce que l'on sait de lui et ce que cela veut dire.
     */
    private void drawDossier(GraphicsContext gc, Constellation map, Node node, double width) {
        double x = width - 300, y = 70, w = 262;
        gc.setFill(INK.deriveColor(0, 1, 1, .9));
        gc.fillRect(x - 16, y - 22, w + 32, panelBottom - (y - 22));
        Device device = node.star().device();
        boolean isDevice = node.device();

        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(MICRO);
        gc.setFill(ACCENT);
        gc.fillText(isDevice ? text("OBJET.OBSERVÉ  ·  APPAREIL", "OBSERVED.OBJECT  ·  DEVICE")
                : text("OBJET.OBSERVÉ  ·  ", "OBSERVED.OBJECT  ·  ") + kindName(node.satellite().kind()), x, y);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(isDevice ? typeName(device.getType()) : "", x + w, y);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(TEXT);
        gc.setFont(HEADING);
        String title = isDevice ? (device.getName() == null ? text("APPAREIL", "DEVICE") : localizedDetail(device.getName()))
                : localizedDetail(node.satellite().label());
        gc.fillText(title.toUpperCase(Locale.ROOT), x, y + 24, w);

        double vx = x, vy = y + 38, vw = w, vh = 236, cx = vx + vw / 2, cy = vy + vh / 2;
        gc.setFill(Color.web("#09090f"));
        gc.fillRect(vx, vy, vw, vh);
        gc.save();
        gc.beginPath();
        gc.rect(vx, vy, vw, vh);
        gc.clip();
        if (isDevice) {
            observeDevice(gc, map, node, cx, cy, vw, vh);
        } else {
            observeSatellite(gc, node, cx, cy, vw, vh);
        }
        matter.flush(gc);
        gc.restore();
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRect(vx + .5, vy + .5, vw - 1, vh - 1);
        gc.setStroke(MUTED);
        for (double[] corner : new double[][] { {vx, vy, 1, 1}, {vx + vw, vy, -1, 1}, {vx, vy + vh, 1, -1}, {vx + vw, vy + vh, -1, -1} }) {
            gc.strokeLine(corner[0], corner[1], corner[0] + corner[2] * 10, corner[1]);
            gc.strokeLine(corner[0], corner[1], corner[0], corner[1] + corner[3] * 10);
        }
        gc.setFont(MICRO);
        gc.setFill(isDevice ? PALETTE[deviceColour(device.getType())] : PALETTE[paletteOf(node.satellite().kind())]);
        gc.fillText(isDevice ? device.getPresence() == Presence.DORMANT ? text("DORMANT", "DORMANT") : text("ACTIF", "ACTIVE")
                : kindName(node.satellite().kind()), vx + 8, vy + vh - 8);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(MUTED);
        gc.fillText(text("EN DIRECT", "LIVE"), vx + vw - 8, vy + vh - 8);
        gc.setTextAlign(TextAlignment.LEFT);

        double rowY = vy + vh + 26;
        for (String[] entry : isDevice ? deviceRows(map, node) : satelliteRows(node)) {
            gc.setFont(MICRO);
            gc.setFill(MUTED);
            gc.fillText(entry[0], x, rowY);
            gc.setFont(CAPTION);
            gc.setFill(TEXT);
            gc.fillText(entry[1], x + 92, rowY, w - 92);
            rowY += 16;
        }

        rowY += 10;
        gc.setFill(ACCENT);
        gc.fillRect(x, rowY - 8, 18, 2);
        gc.setFont(MICRO);
        gc.setFill(TEXT);
        gc.fillText(text("INTERPRÉTATION", "INTERPRETATION"), x + 26, rowY - 4);
        rowY += 14;
        gc.setFont(CAPTION);
        gc.setFill(MUTED);
        for (String sentence : isDevice ? deviceReading(node) : satelliteReading(node)) {
            for (String text : wrap(sentence, 52)) {
                if (rowY > panelBottom - 12) return;
                gc.fillText(text, x, rowY);
                rowY += 13;
            }
            rowY += 4;
        }
    }

    /** L'appareil en grand : sa sphère, ses anneaux, son cortège et ce qui gravite autour de lui. */
    private void observeDevice(GraphicsContext gc, Constellation map, Node node, double cx, double cy,
                               double vw, double vh) {
        Star star = node.star();
        List<Node> around = new ArrayList<>();
        for (Node other : nodes) {
            if (other == node) continue;
            boolean mine = !other.device() && other.star().device().getId().equals(node.id());
            boolean orbiting = other.device() && node.id().equals(other.star().parentId());
            if (mine || orbiting) around.add(other);
        }
        // Le cadre suit le cortège ; les appareils en orbite plus lointaine sont ramenés au bord de
        // la fenêtre, dans leur direction, sinon l'appareil observé n'y serait qu'un point
        double extent = star.coreRadius() * 3.2;
        for (Node other : around) {
            if (!other.device()) {
                extent = Math.max(extent, Math.hypot(other.worldX() - node.worldX(), other.worldY() - node.worldY()) * 1.08);
            }
        }
        double scale = Math.min(vw, vh) * .46 / extent;
        double r = Math.max(24, star.coreRadius() * scale);
        localDust(node.id(), cx, cy, vw, vh);
        gc.setLineWidth(.5);
        gc.setStroke(DUST.deriveColor(0, 1, 1, .18));
        for (double ring : star.rings()) {
            gc.strokeOval(cx - ring * scale, cy - ring * scale, ring * scale * 2, ring * scale * 2);
        }
        gc.setLineWidth(.6);
        for (Node other : around) {
            double dx = other.worldX() - node.worldX(), dy = other.worldY() - node.worldY();
            double distance = Math.max(1e-9, Math.hypot(dx, dy));
            double shown = Math.min(distance * scale, Math.min(vw, vh) * (other.device() ? .44 : .5));
            double sx = cx + dx / distance * shown, sy = cy + dy / distance * shown;
            int colour = other.device() ? deviceColour(other.star().device().getType()) : paletteOf(other.satellite().kind());
            gc.setStroke(PALETTE[colour].deriveColor(0, 1, 1, .14));
            gc.strokeLine(cx, cy, sx, sy);
            if (other.device()) {
                deviceObject(gc, moved(other, sx, sy, Math.max(7, Math.min(16, other.star().coreRadius() * scale * 1.4))));
            } else {
                satelliteObject(gc, moved(other, sx, sy, Math.max(2.4, ConstellationLayout.satelliteRadius(other.satellite().kind()) * scale * 1.6)));
            }
        }
        deviceObject(gc, moved(node, cx, cy, r));
    }

    /** L'objet du cortège en grand, relié à son appareil, et à son port ou à son service. */
    private void observeSatellite(GraphicsContext gc, Node node, double cx, double cy, double vw, double vh) {
        localDust(node.id(), cx, cy, vw, vh);
        Node owner = byId.get(node.star().device().getId());
        double px = cx - vw * .34, py = cy + vh * .3;
        gc.setLineWidth(.7);
        if (owner != null) {
            gc.setStroke(PALETTE[paletteOf(node.satellite().kind())].deriveColor(0, 1, 1, .35));
            gc.strokeLine(px, py, cx, cy);
            deviceObject(gc, moved(owner, px, py, 16));
        }
        Satellite satellite = node.satellite();
        String relatedId = satellite.parentId() != null ? satellite.parentId()
                : satellite.kind() == SatelliteKind.PORT ? satellite.id() + ":service" : null;
        Node related = relatedId == null ? null : byId.get(node.star().device().getId() + '/' + relatedId);
        if (related != null) {
            double sx = cx + vw * .32, sy = cy - vh * .3;
            gc.setStroke(PALETTE[paletteOf(related.satellite().kind())].deriveColor(0, 1, 1, .35));
            gc.strokeLine(cx, cy, sx, sy);
            satelliteObject(gc, moved(related, sx, sy, 16));
        }
        satelliteObject(gc, moved(node, cx, cy, 58));
    }

    private static Node moved(Node node, double x, double y, double radius) {
        return new Node(node.id(), node.star(), node.satellite(), node.worldX(), node.worldY(), x, y, radius, false);
    }

    /** Une poussière propre à la fenêtre d'observation, qui dérive doucement. */
    private void localDust(String key, double cx, double cy, double vw, double vh) {
        for (int i = 0; i < 260; i++) {
            long seed = mix(key.hashCode() * 131L + i);
            double x = cx + (unit(seed, 0) - .5) * vw + (noise(unit(seed, 10) * 40, time * .07) - .5) * 24;
            double y = cy + (unit(seed, 20) - .5) * vh + (noise(unit(seed, 30) * 40 + 9, time * .07) - .5) * 24;
            double twinkle = .6 + .4 * Math.sin(time * (.8 + 2 * unit(seed, 40)) + i);
            matter.add(x, y, 1.1, P_DUST, .24 * twinkle);
        }
    }

    private List<String[]> deviceRows(Constellation map, Node node) {
        Device device = node.star().device();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { text("NATURE", "TYPE"), typeName(device.getType()) });
        rows.add(new String[] { text("ADRESSE", "ADDRESS"), blank(device.getIpAddress()) ? text("INCONNUE", "UNKNOWN") : device.getIpAddress() });
        if (!blank(device.getMacAddress())) rows.add(new String[] { "MAC", device.getMacAddress() });
        rows.add(new String[] { text("PRÉSENCE", "PRESENCE"), device.getPresence() == Presence.ACTIVE
                ? text("ACTIF", "ACTIVE") : text("CONNU, MAIS PAS ACTIF EN CE MOMENT", "KNOWN, BUT NOT CURRENTLY ACTIVE") });
        if (device.getType() != DeviceType.BLUETOOTH) {
            List<PortEndpoint> ports = device.getEndpoints();
            String list = ports.stream().limit(6).map(p -> Integer.toString(p.getPortNumber())).collect(Collectors.joining(" · "));
            rows.add(new String[] { "PORTS", ports.isEmpty() ? text("AUCUN DÉTECTÉ", "NONE DETECTED") : ports.size() + "  ·  " + list + (ports.size() > 6 ? " …" : "") });
            rows.add(new String[] { text("MATÉRIEL", "HARDWARE"), device.getPeripherals().size()
                    + text(" PÉRIPHÉRIQUE(S)  ·  ", " PERIPHERAL(S)  ·  ") + device.getAdapters().size()
                    + text(" CARTE(S)", " ADAPTER(S)") });
        }
        Star parent = node.star().parent();
        rows.add(new String[] { text("RATTACHÉ À", "ATTACHED TO"), parent == null ? text("SYSTÈME À PART ENTIÈRE", "INDEPENDENT SYSTEM")
                : (parent.device().getName() == null ? parent.device().getId()
                        : localizedDetail(parent.device().getName())).toUpperCase(Locale.ROOT) });
        int orbiting = carried.getOrDefault(device.getId(), 0);
        if (orbiting > 0) rows.add(new String[] { text("EN ORBITE", "ORBITING"), orbiting
                + text(" APPAREIL", " DEVICE") + (orbiting > 1 ? "S" : "") });
        return rows;
    }

    private List<String[]> satelliteRows(Node node) {
        Satellite satellite = node.satellite();
        Device device = node.star().device();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] { text("NATURE", "TYPE"), kindName(satellite.kind()) });
        rows.add(new String[] { text("APPAREIL", "DEVICE"), (device.getName() == null
                ? device.getId() : localizedDetail(device.getName())).toUpperCase(Locale.ROOT) });
        rows.add(new String[] { text("LIBELLÉ", "LABEL"), localizedDetail(satellite.label()).toUpperCase(Locale.ROOT) });
        rows.add(new String[] { text("DÉTAIL", "DETAIL"), localizedDetail(satellite.detail()).toUpperCase(Locale.ROOT) });
        PortEndpoint port = endpointOf(node);
        if (port != null) {
            if (!blank(port.getOwner())) rows.add(new String[] { text("PROCESSUS", "PROCESS"), port.getOwner().toUpperCase(Locale.ROOT) });
            if (!blank(port.getLocalAddress())) rows.add(new String[] { text("ÉCOUTE SUR", "LISTENS ON"), port.getLocalAddress() });
            rows.add(new String[] { "SERVICE", port.getName().toUpperCase(Locale.ROOT) + "  ·  "
                    + (port.isServiceVerified() ? text("CONFIRMÉ", "CONFIRMED") : text("INDICATIF", "INFERRED")) });
        }
        return rows;
    }

    /** Ce que disent les couches d'un appareil, en phrases. */
    private List<String> deviceReading(Node node) {
        Device device = node.star().device();
        int orbiting = carried.getOrDefault(device.getId(), 0);
        int ports = device.getEndpoints().size();
        boolean named = device.getName() != null && !device.getName().startsWith("Appareil");
        List<String> reading = new ArrayList<>();
        switch (device.getType()) {
            case GATEWAY_ROUTER -> {
                reading.add(text(
                        "Passerelle : " + orbiting + " appareil" + (orbiting > 1 ? "s passent" : " passe")
                                + " par elle. Sa cage dit qu'elle concentre son système.",
                        "Gateway: " + orbiting + " device" + (orbiting == 1 ? " passes" : "s pass")
                                + " through it. Its cage shows that it is the system hub."));
                Pulse state = pulse;
                if (state != null) {
                    reading.add(state.latencyMs() >= 0
                            ? text("Elle répond en " + state.latencyMs() + " ms à la dernière mesure.",
                                    "It responded in " + state.latencyMs() + " ms on the latest measurement.")
                            : text("Elle n'a pas répondu à la dernière mesure.",
                                    "It did not respond to the latest measurement."));
                }
            }
            case LOCAL_HOST -> reading.add(text(
                    "Cette machine : son cortège montre ce qu'elle expose — " + ports
                            + " port(s) en écoute, " + device.getPeripherals().size() + " périphérique(s), "
                            + device.getAdapters().size() + " carte(s) réseau.",
                    "This machine: its orbital group shows what it exposes — " + ports
                            + " listening port(s), " + device.getPeripherals().size() + " peripheral(s), "
                            + device.getAdapters().size() + " network adapter(s)."));
            case LAN_PEER -> reading.add(named
                    ? text("Identifié : il a livré un nom, d'où son noyau vert.",
                            "Identified: it provided a name, hence its green core.")
                    : text("Anonyme : on ne connaît que son adresse, d'où sa sphère nue et sa petite taille.",
                            "Anonymous: only its address is known, hence its bare sphere and small size."));
            case REMOTE_SERVER -> reading.add(text(
                    "Serveur distant : on n'en voit que la structure, un fil sans matière.",
                    "Remote server: only its structure is visible, a wire without matter."));
            case BLUETOOTH -> reading.add(device.getPresence() == Presence.ACTIVE
                    ? text("Appairé et actif : la balise émet ses ondes, en orbite autour de la machine qui l'a appairé.",
                            "Paired and active: the beacon emits waves while orbiting the machine that paired it.")
                    : text("Appairé mais éteint : la balise ne rayonne plus, estompée, sans affirmer de connexion.",
                            "Paired but inactive: the faded beacon no longer radiates, without implying a connection."));
        }
        if (device.getType() != DeviceType.BLUETOOTH && device.getType() != DeviceType.LOCAL_HOST) {
            reading.add(ports > 0
                    ? text(ports + " port(s) ouvert(s) détecté(s) : ce sont ses amas d'or.",
                            ports + " open port(s) detected: these are its golden clusters.")
                    : text("Aucun port ouvert détecté.", "No open port detected."));
        }
        if (!blank(device.getEvidence())) reading.add(text("Preuve : ", "Evidence: ")
                + localizedDetail(device.getEvidence()) + ".");
        reading.add(text(
                "Le trait entre deux systèmes suit le modèle ; les liens qui s'allument entre voisins sont décoratifs.",
                "The line between two systems follows the model; glowing links between neighbors are decorative."));
        return reading;
    }

    private List<String> satelliteReading(Node node) {
        Satellite satellite = node.satellite();
        List<String> reading = new ArrayList<>();
        switch (satellite.kind()) {
            case ADAPTER -> {
                reading.add(text("Carte réseau : une porte, physique ou virtuelle, vers un réseau.",
                        "Network adapter: a physical or virtual gateway to a network."));
                String exit = node.star().primaryAdapterId();
                if (satellite.id().equals(exit)) reading.add(text(
                        "C'est par elle que l'appareil sort sur le réseau : elle porte son adresse.",
                        "The device reaches the network through it: it carries its address."));
            }
            case PERIPHERAL -> reading.add(text("Matériel branché sur l'appareil, tel que le système le déclare.",
                    "Hardware connected to the device, as reported by the system."));
            case PORT -> reading.add(text("Port en écoute : un programme attend des connexions à cette porte.",
                    "Listening port: a program is waiting for connections at this endpoint."));
            case SERVICE -> {
                PortEndpoint port = endpointOf(node);
                reading.add(port != null && port.isServiceVerified()
                        ? text("Service confirmé : une réponse réelle a été obtenue sur ce port.",
                                "Confirmed service: an actual response was received on this port.")
                        : text("Attribution indicative : le nom vient du numéro de port, pas d'un échange.",
                                "Inferred assignment: the name comes from the port number, not from an exchange."));
            }
        }
        reading.add(text(
                "Il gravite autour de son appareil sur une orbite qui lui est propre ; sa place ne change pas d'un balayage à l'autre.",
                "It orbits its device on its own path; its position remains stable between scans."));
        return reading;
    }

    /** Le port réel derrière un objet de port ou de service, retrouvé par son identifiant. */
    private static PortEndpoint endpointOf(Node node) {
        Satellite satellite = node.satellite();
        if (satellite == null) return null;
        String portId = satellite.kind() == SatelliteKind.SERVICE ? satellite.parentId() : satellite.id();
        if (portId == null || !portId.startsWith("port:")) return null;
        for (PortEndpoint port : node.star().device().getEndpoints()) {
            String id = "port:" + port.getPortNumber() + ':' + port.getLocalAddress() + ':' + port.getOwner();
            if (id.equals(portId)) return port;
        }
        return null;
    }

    /** La latence vers la passerelle, en bandeau vertical, comme la cote d'une planche. */
    private void drawLatency(GraphicsContext gc, double width, double height) {
        Pulse state = pulse;
        if (state == null) return;
        String text = state.latencyMs() >= 0 ? text("LATENCE ", "LATENCY ") + state.latencyMs() + " MS"
                : text("PASSERELLE INJOIGNABLE", "GATEWAY UNREACHABLE");
        double length = text.length() * 6.4 + 18;
        gc.save();
        gc.translate(width - 34, height - 70);
        gc.rotate(-90);
        gc.setFill(state.latencyMs() >= 0 ? ACCENT : MUTED);
        gc.fillRect(0, -12, length, 17);
        gc.setFill(INK);
        gc.setFont(MICRO);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(text, 9, 0);
        gc.restore();
    }

    private void drawInspector(GraphicsContext gc, Node node, double width, double height) {
        Device device = node.star().device();
        String name = node.device()
                ? (device.getName() == null ? text("APPAREIL", "DEVICE")
                        : localizedDetail(device.getName()).toUpperCase(Locale.ROOT)) + "  ·  " + typeName(device.getType())
                : localizedDetail(node.satellite().label()).toUpperCase(Locale.ROOT) + "  ·  " + kindName(node.satellite().kind());
        String info = node.device() ? deviceDetail(device)
                : localizedDetail(node.satellite().detail()).toUpperCase(Locale.ROOT) + "  ·  "
                    + (device.getName() == null ? "" : localizedDetail(device.getName()).toUpperCase(Locale.ROOT));
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

    /**
     * Le balayage en cours. Au centre tant que rien n'est connu, puis replié en haut de la carte
     * pendant le balayage et l'identification. Un anneau de particules tourne autour d'un noyau
     * vert ; la barre rouge suit la progression quand elle est mesurée.
     */
    private void drawLoading(GraphicsContext gc, double width, double height, Loading loading, boolean prominent) {
        double w = prominent ? 360 : 310, h = 76;
        double x = width / 2 - w / 2, y = prominent ? height * .44 - h / 2 : 64;
        gc.setFill(INK.deriveColor(0, 1, 1, .88));
        gc.fillRect(x, y, w, h);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRect(x + .5, y + .5, w - 1, h - 1);

        double rx = x + 36, ry = y + h / 2, rr = 20;
        for (int k = 0; k < 56; k++) {
            double a = k * TAU / 56;
            double head = Math.pow(.5 + .5 * Math.cos(a - time * 2.4), 6);
            matter.add(rx + Math.cos(a) * rr, ry + Math.sin(a) * rr, head > .5 ? 2 : 1.2, P_CYAN, .15 + .85 * head);
        }
        nucleus(gc, rx, ry, 40, 1, 7);
        matter.flush(gc);

        double tx = x + 72, tw = w - 88;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(MICRO);
        gc.setFill(ACCENT);
        gc.fillText(text("DÉCOUVERTE EN COURS", "DISCOVERY IN PROGRESS"), tx, y + 20);
        gc.setFont(LABEL);
        gc.setFill(TEXT);
        gc.fillText(loading.title().toUpperCase(Locale.ROOT), tx, y + 38, tw);
        if (!loading.detail().isBlank()) {
            gc.setFont(CAPTION);
            gc.setFill(MUTED);
            gc.fillText(loading.detail().toUpperCase(Locale.ROOT), tx, y + 53, tw);
        }
        double barY = y + h - 12;
        gc.setFill(FAINT);
        gc.fillRect(tx, barY, tw, 2);
        gc.setFill(ACCENT);
        if (loading.determinate()) {
            gc.fillRect(tx, barY, tw * loading.progress(), 2);
        } else {
            double head = (time * .45) % 1;
            double start = Math.max(0, head - .25);
            gc.fillRect(tx + tw * start, barY, tw * (head - start), 2);
        }
    }

    private void drawHud(GraphicsContext gc, double width, double height, String status) {
        gc.setFont(MICRO);
        gc.setFill(MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(status == null ? "" : status.toUpperCase(Locale.ROOT), 26, height - 26, Math.max(100, width * .4));
        gc.setTextAlign(TextAlignment.RIGHT);
        String help = width > 1200
                ? text("SURVOLER  DÉTAILS     CLIC  OBSERVER     MOLETTE  ZOOM     GLISSER  DÉPLACER     DOUBLE-CLIC  TOUT VOIR",
                        "HOVER  DETAILS     CLICK  OBSERVE     WHEEL  ZOOM     DRAG  MOVE     DOUBLE-CLICK  VIEW ALL")
                : text("CLIC  OBSERVER     MOLETTE  ZOOM     DOUBLE-CLIC  TOUT VOIR",
                        "CLICK  OBSERVE     WHEEL  ZOOM     DOUBLE-CLICK  VIEW ALL");
        gc.fillText(help, width - 60, height - 26);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRect(.5, .5, width - 1, height - 1);
    }

    // --- Utilitaires ------------------------------------------------------------------------------

    private Node nearest(double x, double y) {
        Node best = null;
        double score = Double.POSITIVE_INFINITY;
        for (Node node : nodes) {
            double reach = Math.max(5, node.radius() * (node.device() ? 1.4 : 1.1)) + 6;
            double distance = Math.hypot(x - node.x(), y - node.y()) / reach;
            if (distance < 1 && distance < score) {
                score = distance;
                best = node;
            }
        }
        return best;
    }

    private String deviceSummary(Device device) {
        if (device.getType() == DeviceType.BLUETOOTH) {
            return device.getPresence() == Presence.ACTIVE ? text("ACTIF", "ACTIVE") : "DORMANT";
        }
        String address = shortAddress(device.getIpAddress());
        int ports = device.getEndpoints().size();
        return ports == 0 ? address : address + "   ·   " + ports + " PORT" + (ports > 1 ? "S" : "");
    }

    private String deviceDetail(Device device) {
        if (device.getType() == DeviceType.BLUETOOTH) {
            // Ce qu'est l'appareil et l'état réel de sa liaison passent avant l'adresse
            return (localizedDetail(device.getEvidence()) + "  ·  " + device.getMacAddress()).toUpperCase(Locale.ROOT);
        }
        String base = device.getIpAddress() + "  ·  " + device.getMacAddress() + "  ·  "
                + device.getEndpoints().size() + " PORT(S)  ·  " + device.getPeripherals().size()
                + text(" PÉRIPHÉRIQUE(S)", " PERIPHERAL(S)");
        return (device.getEvidence().isBlank() ? base : base + "  ·  " + localizedDetail(device.getEvidence())).toUpperCase(Locale.ROOT);
    }

    private static String shortAddress(String address) {
        if (address == null || address.isBlank()) return "";
        if (!address.contains(":")) return address;
        String[] parts = address.split(":");
        if (parts.length < 3) return address;
        return "…:" + parts[parts.length - 2] + ':' + parts[parts.length - 1];
    }

    private String typeName(DeviceType type) {
        return switch (type) {
            case GATEWAY_ROUTER -> text("PASSERELLE", "GATEWAY");
            case LOCAL_HOST -> text("CETTE MACHINE", "THIS MACHINE");
            case LAN_PEER -> text("VOISIN DU RÉSEAU", "NETWORK NEIGHBOR");
            case REMOTE_SERVER -> text("SERVEUR DISTANT", "REMOTE SERVER");
            case BLUETOOTH -> "BLUETOOTH";
        };
    }

    private String kindName(SatelliteKind kind) {
        return switch (kind) {
            case ADAPTER -> text("CARTE RÉSEAU", "NETWORK ADAPTER");
            case PERIPHERAL -> text("PÉRIPHÉRIQUE", "PERIPHERAL");
            case PORT -> "PORT";
            case SERVICE -> "SERVICE";
        };
    }

    private String localizedDetail(String value) {
        if (language == UiLanguage.FRENCH || value == null) return value;
        return value
                .replace("VPN / Virtuel", "VPN / Virtual")
                .replace("Périphérique de saisie", "Input device")
                .replace("Manette", "Controller")
                .replace("Écran", "Display")
                .replace("Stockage", "Storage")
                .replace("Autre", "Other")
                .replace("Service confirmé", "Confirmed service")
                .replace("Attribution indicative", "Inferred assignment")
                .replace("Casque ou écouteurs", "Headset or headphones")
                .replace("Clavier, souris ou manette", "Keyboard, mouse or controller")
                .replace("Téléphone", "Phone")
                .replace("Ordinateur", "Computer")
                .replace("Objet porté", "Wearable")
                .replace("Appareil Bluetooth", "Bluetooth device")
                .replace("état de connexion inconnu", "connection status unknown")
                .replace("appairé, non connecté", "paired, not connected")
                .replace("connecté", "connected")
                .replace("dernière connexion le", "last connected on")
                .replace("Route par défaut", "Default route")
                .replace("Adresse MAC privée, aucun nom annoncé", "Private MAC address, no name advertised")
                .replace("Aucun nom annoncé", "No name advertised")
                .replace("Aucune signature reçue", "No signature received")
                .replace("Port souvent utilisé par", "Port commonly used by")
                .replace("Passerelle", "Gateway")
                .replace("Appareil", "Device")
                .replace("Inconnu", "Unknown")
                .replace(" janvier ", " January ")
                .replace(" février ", " February ")
                .replace(" mars ", " March ")
                .replace(" avril ", " April ")
                .replace(" mai ", " May ")
                .replace(" juin ", " June ")
                .replace(" juillet ", " July ")
                .replace(" août ", " August ")
                .replace(" septembre ", " September ")
                .replace(" octobre ", " October ")
                .replace(" novembre ", " November ")
                .replace(" décembre ", " December ");
    }

    private static int deviceColour(DeviceType type) {
        return switch (type) {
            case GATEWAY_ROUTER -> P_TEAL;
            case LOCAL_HOST, LAN_PEER -> P_BLUE;
            case REMOTE_SERVER -> P_WHITE;
            case BLUETOOTH -> P_CYAN;
        };
    }

    private static int paletteOf(SatelliteKind kind) {
        return switch (kind) {
            case ADAPTER -> P_BLUE;
            case PERIPHERAL -> P_WHITE;
            case PORT -> P_GOLD;
            case SERVICE -> P_CYAN;
        };
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

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** 0 si le point est dans le cadre, sinon sa distance au bord le plus proche. */
    private static double offScreen(double x, double y, double width, double height) {
        return Math.hypot(Math.max(0, Math.max(-x, x - width)), Math.max(0, Math.max(-y, y - height)));
    }

    private static boolean onScreen(double x, double y, double margin, double width, double height) {
        return x > -margin && y > -margin && x < width + margin && y < height + margin;
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
        Set<Long> seen = new LinkedHashSet<>();
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

    /** Le polyèdre dual : un sommet au centre de chaque triangle, relié à ceux des triangles voisins. */
    private static int[] dual(List<double[]> vertices, List<int[]> faces, double[][] centres) {
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
        return pairs.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double[] normalized(double[] p) {
        double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        return new double[] { p[0] / length, p[1] / length, p[2] / length };
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
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
            Set<Long> seen = new LinkedHashSet<>();
            for (int i = 0; i < n; i++) {
                int[] best = { -1, -1, -1 };
                double[] dist = { 9, 9, 9 };
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    double dx = p[i][0] - p[j][0], dy = p[i][1] - p[j][1], dz = p[i][2] - p[j][2];
                    double d = dx * dx + dy * dy + dz * dz;
                    for (int k = 0; k < 3; k++) {
                        if (d < dist[k]) {
                            for (int m = 2; m > k; m--) {
                                dist[m] = dist[m - 1];
                                best[m] = best[m - 1];
                            }
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
        while (size < wanted && size < 768) size *= 2;
        return size;
    }
}
