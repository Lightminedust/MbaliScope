package org.mbali.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkLink;
import org.mbali.view.ConstellationLayout.Constellation;
import org.mbali.view.ConstellationLayout.Satellite;
import org.mbali.view.ConstellationLayout.SatelliteKind;
import org.mbali.view.ConstellationLayout.Star;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Carte d'un écosystème, et moniteur.
 *
 * Deux natures de liens : le lien structurel, qui suit la réalité du réseau, et le
 * lien de voisinage, qui naît entre deux objets de même nature quand ils se
 * rapprochent et s'éteint quand ils s'éloignent. Un éclat parcourt chaque trait.
 *
 * Le niveau de détail ne concerne que le cortège — ports, services, matériels,
 * cartes : de loin, ils se réduisent à des points. Les appareils, eux, gardent
 * toujours leur symbole et leur nom, et la géométrie de la carte reste toujours
 * tracée : c'est elle qui dit où se trouve le centre.
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

    private static final Color NIGHT = Color.web("#070914");
    private static final Color NIGHT_DEEP = Color.web("#02040b");
    private static final Color GOLD = Color.web("#e4cda2");
    private static final Color GOLD_DIM = Color.web("#8d7650");
    private static final Color CYAN = Color.web("#80d9df");
    private static final Color BLUE = Color.web("#7188d9");
    private static final Color VIOLET = Color.web("#b497e8");
    private static final Color TEXT = Color.web("#f0e6d2");
    private static final Color TEXT_DIM = Color.web("#9a8c72");

    private static final Font TITLE_FONT = Font.font("Segoe UI Semibold", 13);
    private static final Font SMALL_FONT = Font.font("Consolas", 10.5);
    private static final double HUD_BOTTOM = 30;
    private static final double HIT_RADIUS = 15;

    /**
     * Rayons VRAIS, en unités du monde, et non en pixels d'écran.
     *
     * Ils étaient exprimés en pixels et multipliés par 1/zoom, donc chaque objet
     * gardait la même taille à l'écran : en s'éloignant, tout enflait et la carte
     * perdait son échelle. Ici un objet a une taille propre — on s'approche, il
     * grossit ; on s'éloigne, il diminue, comme un astre sur une planche du ciel.
     */
    private static final double ADAPTER_R = 1_700;
    private static final double PERIPHERAL_R = 1_400;
    private static final double PORT_R = 1_200;
    private static final double SERVICE_R = 750;

    /** En deçà de cette taille à l'écran, un objet ne montre plus sa forme. */
    private static final double SHAPE_ABOVE_PX = 3.0;

    /** Plancher résiduel : un objet sous le pixel ne doit pas disparaître pour autant. */
    private static final double NEVER_BELOW_PX = 1.6;

    private static final int MAX_NEIGHBOUR_LINKS = 260;

    /** Sous ce seuil, le seul cortège se réduit à des points. Les appareils, jamais. */
    private static final double DETAIL_FROM = 0.006;
    private static final double DETAIL_TO = 0.017;
    private static final double PLAIN_DOT_BELOW = 0.05;

    private record Target(String id, String title, String detail, double screenX, double screenY) {
    }

    private record Placed(Star star, Satellite node, String id, double x, double y,
                          double screenX, double screenY, boolean match) {
    }

    private record Box(double x, double y, double width, double height) {
        boolean hits(Box other) {
            return x < other.x + other.width && x + width > other.x
                    && y < other.y + other.height && y + height > other.y;
        }
    }

    private record Neighbour(double x1, double y1, double x2, double y2,
                             double strength, int seed, Color color) {
    }

    private final double[] fieldX = new double[260];
    private final double[] fieldY = new double[260];
    private final double[] fieldSize = new double[260];

    /**
     * L'état du battement, tel que le rendu a besoin de le connaître. La vue ignore
     * volontairement d'où il vient : elle ne dépend d'aucune classe de service.
     */
    public record Pulse(int latencyMs, int neighbours, long ageMillis, double[] history) {
    }

    private Pulse pulse;

    private final List<Target> targets = new ArrayList<>();
    private final List<Box> labelBoxes = new ArrayList<>();
    private final Map<SatelliteKind, int[]> census = new EnumMap<>(SatelliteKind.class);

    public ConstellationRenderer() {
        Random random = new Random(19770905L);
        for (int i = 0; i < fieldX.length; i++) {
            fieldX[i] = random.nextDouble();
            fieldY[i] = random.nextDouble();
            fieldSize[i] = 0.5 + random.nextDouble() * 1.3;
        }
    }

    public void draw(GraphicsContext gc, Constellation map, Camera camera, double width, double height,
                     double seconds, double mouseX, double mouseY, boolean mouseInside,
                     String statusText, String selectedId, Filter filter, Loading loading) {
        Filter active = filter == null ? Filter.all() : filter;
        double detail = ramp(camera.zoom(), DETAIL_FROM, DETAIL_TO);

        drawNight(gc, width, height);
        targets.clear();
        labelBoxes.clear();
        census.clear();

        if (map != null) {
            List<Placed> placed = place(map, camera, seconds, width, height, active);

            gc.save();
            gc.translate(camera.offsetX(), camera.offsetY());
            gc.scale(camera.zoom(), camera.zoom());

            // La géométrie de la carte est permanente : elle situe le centre.
            drawPolarGrid(gc, map, camera);
            drawOrbits(gc, map, camera, seconds);
            drawStructuralLinks(gc, map, camera, seconds, width, height, active);
            drawDeviceLinks(gc, map, camera, seconds);
            drawNeighbourLinks(gc, placed, camera, seconds);
            drawNodes(gc, placed, camera, selectedId, active);
            for (Star star : map.stars().values()) {
                drawDevice(gc, star, camera, seconds, selectedId);
            }
            gc.restore();

            Target shown = selectedId == null ? null : find(selectedId);
            if (shown == null && mouseInside) {
                shown = nearest(mouseX, mouseY);
            }
            // Les noms viennent après : ils ont besoin de savoir ce qui est survolé.
            drawDeviceNames(gc, map, camera, seconds, width, height,
                    selectedId, shown == null ? null : shown.id(), active);
            drawMonitor(gc, width, active, detail);
            drawLive(gc, width, seconds);
            if (shown != null) {
                drawInspector(gc, shown, height);
            }
        }
        if (loading != null) {
            drawLoading(gc, width, height, seconds, loading,
                    map == null || map.stars().isEmpty());
        }
        drawHud(gc, width, height, statusText);
        drawFrame(gc, width, height);
    }

    /** Le battement, publié depuis le thread graphique. */
    public void setPulse(Pulse pulse) {
        this.pulse = pulse;
    }

    /**
     * L'indicateur temps réel : latence mesurée vers la passerelle et voisins connus.
     *
     * Le point vif respire au lieu de clignoter — un seuil dur produisait exactement
     * le scintillement qu'on a passé du temps à supprimer sur la carte.
     */
    private void drawLive(GraphicsContext gc, double width, double seconds) {
        Pulse state = pulse;
        if (state == null) {
            return;
        }
        double x = width - 216;
        double y = 74 + 110;

        double breath = .45 + .55 * (0.5 + 0.5 * Math.sin(seconds * 1.9));
        gc.setFill((state.latencyMs() >= 0 ? CYAN : GOLD_DIM).deriveColor(0, 1, 1, breath));
        gc.fillOval(x, y - 5, 7, 7);

        gc.setFont(SMALL_FONT);
        gc.setFill(TEXT_DIM);
        gc.fillText("TEMPS RÉEL", x + 14, y);

        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(state.ageMillis() < 0 ? "" : "il y a " + state.ageMillis() / 1000 + " s",
                x + 192, y);
        gc.setTextAlign(TextAlignment.LEFT);

        gc.setFill(TEXT);
        gc.fillText(state.latencyMs() >= 0
                ? "PASSERELLE  " + state.latencyMs() + " ms"
                : "PASSERELLE  INJOIGNABLE", x, y + 18);
        gc.setFill(TEXT_DIM);
        gc.fillText("VOISINS CONNUS  " + state.neighbours(), x, y + 34);

        drawSparkline(gc, x, y + 44, 192, 26, state.history());
    }

    /** La courbe des dernières mesures, normalisée sur la plus lente d'entre elles. */
    private void drawSparkline(GraphicsContext gc, double x, double y,
                               double width, double height, double[] history) {
        if (history == null || history.length < 2) {
            return;
        }
        double worst = 1;
        for (double value : history) {
            worst = Math.max(worst, value);
        }
        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .35));
        gc.setLineWidth(1);
        gc.strokeLine(x, y + height, x + width, y + height);

        gc.setStroke(CYAN.deriveColor(0, 1, 1, .8));
        gc.setLineWidth(1.4);
        double step = width / (history.length - 1);
        for (int i = 1; i < history.length; i++) {
            double previous = history[i - 1] < 0 ? 0 : history[i - 1];
            double current = history[i] < 0 ? 0 : history[i];
            gc.strokeLine(x + (i - 1) * step, y + height - previous / worst * height,
                    x + i * step, y + height - current / worst * height);
        }
    }

    /**
     * Le cadre de la fenêtre. Elle n'a plus de bordure système : ce filet et ses quatre
     * équerres tiennent ce rôle, dans le trait gravé du reste de la carte.
     */
    private void drawFrame(GraphicsContext gc, double width, double height) {
        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .55));
        gc.setLineWidth(1);
        gc.strokeRect(.5, .5, width - 1, height - 1);

        gc.setStroke(GOLD.deriveColor(0, 1, 1, .7));
        double tick = 16;
        gc.strokeLine(.5, .5, tick, .5);
        gc.strokeLine(.5, .5, .5, tick);
        gc.strokeLine(width - tick, .5, width - .5, .5);
        gc.strokeLine(width - .5, .5, width - .5, tick);
        gc.strokeLine(.5, height - tick, .5, height - .5);
        gc.strokeLine(.5, height - .5, tick, height - .5);
        gc.strokeLine(width - tick, height - .5, width - .5, height - .5);
        gc.strokeLine(width - .5, height - tick, width - .5, height - .5);
    }

    public String hitTest(Constellation map, Camera camera, double screenX, double screenY) {
        Target target = nearest(screenX, screenY);
        return target == null ? null : target.id();
    }

    /** Une seule évaluation des positions par image, réutilisée partout. */
    private List<Placed> place(Constellation map, Camera camera, double seconds,
                               double width, double height, Filter filter) {
        List<Placed> placed = new ArrayList<>();
        for (Star star : map.stars().values()) {
            for (Satellite node : star.satellites()) {
                int[] counts = census.computeIfAbsent(node.kind(), key -> new int[2]);
                counts[1]++;
                if (!filter.showsKind(node.kind())) {
                    continue;
                }
                boolean match = filter.matches(node);
                if (filter.searching() && !match) {
                    continue;
                }
                counts[0]++;

                double[] point = ConstellationLayout.position(star, node, seconds);
                double sx = camera.screenX(point[0]);
                double sy = camera.screenY(point[1]);
                if (sx < -60 || sx > width + 60 || sy < -60 || sy > height + 60) {
                    continue; // hors cadre : un fort zoom ne montre que son sujet
                }
                String id = star.device().getId() + '/' + node.id();
                placed.add(new Placed(star, node, id, point[0], point[1], sx, sy, match));
                targets.add(new Target(id, node.label(), node.detail(), sx, sy));
            }
        }
        return placed;
    }

    private void drawNight(GraphicsContext gc, double width, double height) {
        gc.setFill(NIGHT);
        gc.fillRect(0, 0, width, height);
        gc.setFill(NIGHT_DEEP);
        gc.fillOval(-width * .25, height * .36, width * 1.5, height * 1.15);
        gc.setFill(Color.WHITE.deriveColor(0, 1, 1, .12));
        for (int i = 0; i < fieldX.length; i++) {
            gc.fillOval(fieldX[i] * width, fieldY[i] * height, fieldSize[i], fieldSize[i]);
        }
    }

    /**
     * Décor géométrique emprunté aux cartes du ciel. Toujours tracé, quelle que soit
     * l'échelle : c'est le repère qui désigne le centre, et sans lui la vue d'ensemble
     * n'est qu'une poignée de points perdus dans le noir.
     */
    private void drawPolarGrid(GraphicsContext gc, Constellation map, Camera camera) {
        double radius = map.totalRadius() * 1.04;
        double squash = 0.74;
        gc.setLineWidth(.9 / camera.zoom());

        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .15));
        for (int ring = 1; ring <= 5; ring++) {
            double r = radius * ring / 5.0;
            gc.strokeOval(-r, -r * squash, r * 2, r * squash * 2);
        }
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6;
            gc.strokeLine(0, 0, Math.cos(angle) * radius, Math.sin(angle) * radius * squash);
        }

        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .34));
        double band = radius * .02;
        gc.strokeOval(-radius, -radius * squash, radius * 2, radius * squash * 2);
        for (int i = 0; i < 120; i++) {
            double angle = i * Math.PI * 2 / 120;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle) * squash;
            double inner = i % 10 == 0 ? radius - band : radius - band * .5;
            gc.strokeLine(cos * inner, sin * inner, cos * radius, sin * radius);
        }

        // Croix centrale : le repère du centre, lisible même très dézoomé
        double cross = Math.max(radius * .012, 10 / camera.zoom());
        gc.setStroke(GOLD.deriveColor(0, 1, 1, .5));
        gc.strokeLine(-cross, 0, cross, 0);
        gc.strokeLine(0, -cross * squash, 0, cross * squash);
    }

    /**
     * Le seul trait qui subsiste : celui qui relie deux systèmes.
     *
     * À l'intérieur d'un système, l'orbite dit déjà l'appartenance — un port tourne
     * autour de sa machine, un téléphone autour de sa box — et un trait vers le centre
     * ne faisait que répéter ce que le mouvement montre. Entre deux systèmes, en
     * revanche, aucune orbite ne peut l'exprimer : le trait est alors la seule façon
     * de dire qu'ils communiquent.
     *
     * Il part de la carte réseau qui porte l'adresse de l'appareil, et non de son
     * noyau, parce que c'est par elle qu'il sort.
     */
    private void drawStructuralLinks(GraphicsContext gc, Constellation map, Camera camera,
                                     double seconds, double width, double height, Filter filter) {
        int seed = 0;
        for (NetworkLink link : map.links()) {
            if (link.source() == null || link.target() == null) {
                continue;
            }
            Star from = map.starForId(link.source().getId());
            Star to = map.starForId(link.target().getId());
            if (from == null || to == null || from == to
                    || !from.isSystemRoot() || !to.isSystemRoot()) {
                continue;
            }
            double[] start = exitPoint(from, seconds, filter);
            double[] end = exitPoint(to, seconds, filter);
            double alpha = .34 * lengthFade(start[0], start[1], end[0], end[1], camera, width, height);
            if (alpha < .02) {
                continue;
            }
            glowLine(gc, start[0], start[1], end[0], end[1], camera, seconds,
                    ++seed * 13, GOLD, alpha);
        }
    }

    /**
     * Le point par lequel un appareil rejoint l'extérieur : sa carte réseau principale.
     *
     * Si les adaptateurs sont masqués par un filtre, le trait repart du noyau : il
     * aboutissait sinon sur un objet que l'utilisateur ne voit pas.
     */
    private double[] exitPoint(Star star, double seconds, Filter filter) {
        Satellite primary = star.primaryAdapterId() == null || !filter.showsKind(SatelliteKind.ADAPTER)
                ? null : star.byId().get(star.primaryAdapterId());
        if (primary == null) {
            return new double[] { ConstellationLayout.starX(star, seconds),
                    ConstellationLayout.starY(star, seconds) };
        }
        return ConstellationLayout.position(star, primary, seconds);
    }

    /**
     * Atténuation d'un trait structurel : ce qui compte est de savoir si son
     * extrémité lointaine est réellement dans le champ. Un lien qui part vers un
     * appareil hors cadre n'apprend rien et doit s'effacer.
     */
    private double lengthFade(double x1, double y1, double x2, double y2,
                              Camera camera, double width, double height) {
        double margin = Math.min(width, height) * .5;
        double farOff = offScreenDistance(camera.screenX(x2), camera.screenY(y2), width, height);
        double nearOff = offScreenDistance(camera.screenX(x1), camera.screenY(y1), width, height);
        double worst = Math.max(farOff, nearOff);
        return 1 - ramp(worst, margin * .15, margin);
    }

    /** 0 si le point est dans le cadre, sinon sa distance au bord le plus proche. */
    private static double offScreenDistance(double screenX, double screenY,
                                            double width, double height) {
        double dx = Math.max(0, Math.max(-screenX, screenX - width));
        double dy = Math.max(0, Math.max(-screenY, screenY - height));
        return Math.hypot(dx, dy);
    }

    /**
     * Les liens de voisinage. Deux objets de même nature se relient sous le seuil de
     * leur famille, d'autant plus franchement qu'ils sont proches.
     */
    private void drawNeighbourLinks(GraphicsContext gc, List<Placed> placed, Camera camera,
                                    double seconds) {
        Map<SatelliteKind, List<Placed>> families = new EnumMap<>(SatelliteKind.class);
        for (Placed item : placed) {
            families.computeIfAbsent(item.node().kind(), key -> new ArrayList<>()).add(item);
        }

        List<Neighbour> links = new ArrayList<>();
        families.forEach((kind, members) -> {
            double reach = ConstellationLayout.linkReach(kind);
            Color color = colorFor(kind);
            for (int i = 0; i < members.size(); i++) {
                Placed a = members.get(i);
                for (int j = i + 1; j < members.size(); j++) {
                    Placed b = members.get(j);
                    double dx = a.x() - b.x();
                    double dy = a.y() - b.y();
                    if (Math.abs(dx) > reach || Math.abs(dy) > reach) {
                        continue;
                    }
                    double strength = ConstellationLayout.linkStrength(kind, Math.hypot(dx, dy));
                    if (strength > 0.02) {
                        links.add(new Neighbour(a.x(), a.y(), b.x(), b.y(), strength,
                                a.id().hashCode() ^ b.id().hashCode(), color));
                    }
                }
            }
        });

        links.sort(Comparator.comparingDouble(Neighbour::strength).reversed());
        int drawn = 0;
        for (Neighbour link : links) {
            if (drawn++ >= MAX_NEIGHBOUR_LINKS) {
                break;
            }
            glowLine(gc, link.x1(), link.y1(), link.x2(), link.y2(), camera, seconds,
                    link.seed(), link.color(), .16 + .34 * link.strength());
        }
    }

    /**
     * Un trait d'opacité constante, parcouru par un éclat : l'information circule,
     * la ligne ne clignote pas.
     */
    private void glowLine(GraphicsContext gc, double x1, double y1, double x2, double y2,
                          Camera camera, double seconds, int seed, Color color, double alpha) {
        gc.setLineWidth(.8 / camera.zoom());
        gc.setStroke(color.deriveColor(0, 1, 1, alpha * .55));
        gc.strokeLine(x1, y1, x2, y2);

        double head = ((seconds * .24 + Math.floorMod(seed, 100) / 100.0) % 1.0);
        double tail = Math.max(0, head - .16);
        gc.setLineWidth(1.7 / camera.zoom());
        gc.setStroke(color.interpolate(Color.WHITE, .45).deriveColor(0, 1, 1, alpha));
        gc.strokeLine(lerp(x1, x2, tail), lerp(y1, y2, tail),
                lerp(x1, x2, head), lerp(y1, y2, head));
    }

    /**
     * Le cortège, et lui seul, perd son détail avec la distance : de loin, un port
     * ou un service n'est plus qu'un point coloré.
     */
    private void drawNodes(GraphicsContext gc, List<Placed> placed, Camera camera,
                           String selectedId, Filter filter) {
        double s = 1 / camera.zoom();
        for (Placed item : placed) {
            double x = item.x();
            double y = item.y();
            SatelliteKind kind = item.node().kind();
            double radius = worldRadius(kind);

            // Chaque objet décide selon SA taille à l'écran, et non selon un seuil de
            // zoom commun : c'est la conséquence d'avoir des tailles vraies. Un service
            // se simplifie donc bien avant un adaptateur, ce qui est juste.
            if (radius * camera.zoom() < SHAPE_ABOVE_PX) {
                double r = Math.max(radius, NEVER_BELOW_PX * s);
                gc.setFill(colorFor(kind).deriveColor(0, 1, 1, .85));
                gc.fillOval(x - r, y - r, r * 2, r * 2);
                continue;
            }
            if (item.id().equals(selectedId)) {
                halo(gc, x, y, radius * 1.9, VIOLET, s);
            } else if (filter.searching() && item.match()) {
                halo(gc, x, y, radius * 1.9, GOLD, s);
            }
            switch (kind) {
                case ADAPTER -> drawAdapter(gc, x, y, radius, s);
                case PERIPHERAL -> drawPeripheral(gc, x, y, radius, s);
                case PORT -> drawPort(gc, x, y, radius, s);
                case SERVICE -> drawService(gc, x, y, radius, s);
            }
        }
    }

    /** Carte réseau : une petite rose des vents, écho du symbole de la machine. */
    private void drawAdapter(GraphicsContext gc, double x, double y, double r, double s) {
        gc.setFill(NIGHT_DEEP);
        gc.setStroke(CYAN.deriveColor(0, 1, 1, .95));
        gc.setLineWidth(1.1 * s);
        gc.fillOval(x - r, y - r, r * 2, r * 2);
        gc.strokeOval(x - r, y - r, r * 2, r * 2);
        gc.strokeOval(x - r * .52, y - r * .52, r * 1.04, r * 1.04);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + Math.PI / 4;
            gc.strokeLine(x + Math.cos(a) * r, y + Math.sin(a) * r,
                    x + Math.cos(a) * r * 1.45, y + Math.sin(a) * r * 1.45);
        }
    }

    /** Périphérique : un corps cerclé, comme une planète à anneau. */
    private void drawPeripheral(GraphicsContext gc, double x, double y, double r, double s) {
        gc.setFill(BLUE.deriveColor(0, 1, 1, .95));
        gc.fillOval(x - r * .72, y - r * .72, r * 1.44, r * 1.44);
        gc.setStroke(GOLD.deriveColor(0, 1, 1, .8));
        gc.setLineWidth(.9 * s);
        gc.strokeOval(x - r * 1.5, y - r * .55, r * 3, r * 1.1);
    }

    /** Port : un hexagone gravé, ouvert au centre. */
    private void drawPort(GraphicsContext gc, double x, double y, double r, double s) {
        double[] xs = new double[6];
        double[] ys = new double[6];
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + Math.PI / 6;
            xs[i] = x + Math.cos(a) * r;
            ys[i] = y + Math.sin(a) * r;
        }
        gc.setFill(NIGHT_DEEP);
        gc.setStroke(GOLD);
        gc.setLineWidth(1.05 * s);
        gc.fillPolygon(xs, ys, 6);
        gc.strokePolygon(xs, ys, 6);
        gc.setFill(GOLD);
        gc.fillOval(x - r * .3, y - r * .3, r * .6, r * .6);
    }

    /** Service : une étoile à quatre branches, désormais franchement visible. */
    private void drawService(GraphicsContext gc, double x, double y, double r, double s) {
        double w = r * .34;
        double[] xs = { x, x + w, x + r, x + w, x, x - w, x - r, x - w };
        double[] ys = { y - r, y - w, y, y + w, y + r, y + w, y, y - w };
        gc.setFill(VIOLET.deriveColor(0, 1, 1, .95));
        gc.fillPolygon(xs, ys, 8);
        gc.setStroke(VIOLET.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, .7));
        gc.setLineWidth(.7 * s);
        gc.strokePolygon(xs, ys, 8);
    }

    /**
     * Le tracé d'orbite : le chemin que suit un appareil autour de celui dont il
     * dépend, à la manière d'une planche d'astronomie.
     *
     * C'est ce qui remplace le trait supprimé. Un trait affirmait l'appartenance en
     * l'écrivant ; l'orbite la montre, et dit en plus à quelle distance l'appareil
     * gravite. La ligne est tirée depuis la position vivante de l'hôte, donc elle
     * danse avec lui.
     */
    private void drawOrbits(GraphicsContext gc, Constellation map, Camera camera, double seconds) {
        double s = 1 / camera.zoom();
        gc.setLineWidth(.7 * s);
        for (Star star : map.stars().values()) {
            Star host = star.parent();
            if (host == null) {
                continue;
            }
            // On retrouve le rayon d'avant l'aplatissement, pour redessiner l'ellipse
            // exactement sur laquelle l'appareil se déplace.
            double radius = Math.hypot(star.homeX(), star.homeY() / 0.74);
            double rx = radius;
            double ry = radius * 0.74;
            double hostX = ConstellationLayout.starX(host, seconds);
            double hostY = ConstellationLayout.starY(host, seconds);

            gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .20));
            gc.setLineDashes(15 * s, 13 * s);
            gc.strokeOval(hostX - rx, hostY - ry, rx * 2, ry * 2);
            gc.setLineDashes();

            // Quelques repères sur la course, comme les graduations d'une planche
            gc.setFill(GOLD_DIM.deriveColor(0, 1, 1, .34));
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6;
                double tick = 1.4 * s;
                gc.fillOval(hostX + Math.cos(angle) * rx - tick,
                        hostY + Math.sin(angle) * ry - tick, tick * 2, tick * 2);
            }
        }
    }

    /**
     * La constellation des appareils : deux appareils proches se relient, et le trait
     * s'éteint quand ils s'écartent — la même règle que pour les objets d'une famille,
     * portée à l'échelle des astres.
     */
    private void drawDeviceLinks(GraphicsContext gc, Constellation map, Camera camera,
                                 double seconds) {
        List<Star> stars = new ArrayList<>(map.stars().values());
        for (int i = 0; i < stars.size(); i++) {
            double ax = ConstellationLayout.starX(stars.get(i), seconds);
            double ay = ConstellationLayout.starY(stars.get(i), seconds);
            for (int j = i + 1; j < stars.size(); j++) {
                double bx = ConstellationLayout.starX(stars.get(j), seconds);
                double by = ConstellationLayout.starY(stars.get(j), seconds);
                double strength = ConstellationLayout.deviceLinkStrength(Math.hypot(ax - bx, ay - by));
                if (strength <= .02) {
                    continue;
                }
                glowLine(gc, ax, ay, bx, by, camera, seconds,
                        stars.get(i).device().getId().hashCode()
                                ^ stars.get(j).device().getId().hashCode(),
                        CYAN, .08 + .24 * strength);
            }
        }
    }

    /**
     * Un appareil garde toujours son symbole entier : c'est le repère de la carte, et
     * le réduire à un point au loin était une erreur.
     */
    private void drawDevice(GraphicsContext gc, Star star, Camera camera, double seconds,
                            String selectedId) {
        double s = 1 / camera.zoom();
        double x = ConstellationLayout.starX(star, seconds);
        double y = ConstellationLayout.starY(star, seconds);
        // La taille vraie de l'appareil. Le plancher ne sert plus qu'à l'empêcher de
        // disparaître sous le pixel : un plancher large gonflait la box et cette
        // machine dès qu'on s'éloignait, ce qui ruinait l'échelle de la carte.
        double r = Math.max(star.coreRadius(), 2.2 * s);
        Device device = star.device();

        targets.add(new Target(device.getId(), device.getName(), deviceDetail(device),
                camera.screenX(x), camera.screenY(y)));
        if (device.getId().equals(selectedId)) {
            halo(gc, x, y, r + 12 * s, GOLD, s);
        }

        gc.setFill(NIGHT_DEEP);
        gc.setStroke(GOLD.deriveColor(0, 1, 1, .88));
        gc.setLineWidth(1.15 * s);
        gc.fillOval(x - r, y - r, r * 2, r * 2);
        gc.strokeOval(x - r, y - r, r * 2, r * 2);

        if (device.getType() == DeviceType.LOCAL_HOST) {
            gc.strokeOval(x - r * 1.32, y - r * 1.32, r * 2.64, r * 2.64);
            gc.setFill(GOLD);
            gc.fillOval(x - r * .46, y - r * .46, r * .92, r * .92);
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                gc.strokeLine(x + Math.cos(a) * r * .62, y + Math.sin(a) * r * .62,
                        x + Math.cos(a) * r * .9, y + Math.sin(a) * r * .9);
            }
        } else if (device.getType() == DeviceType.GATEWAY_ROUTER) {
            // Un phare, et non un appareil de plus. C'est par lui que passe tout son
            // système : il doit se reconnaître d'un seul coup d'œil, au lieu du losange
            // discret qu'on prenait pour un voisin quelconque.
            gc.setStroke(GOLD.deriveColor(0, 1, 1, .92));
            gc.strokeOval(x - r * 1.48, y - r * 1.48, r * 2.96, r * 2.96);
            gc.setLineDashes(9 * s, 7 * s);
            gc.strokeOval(x - r * 1.84, y - r * 1.84, r * 3.68, r * 3.68);
            gc.setLineDashes();
            for (int i = 0; i < 8; i++) {
                double sweep = Math.toDegrees(i * Math.PI / 4 + Math.PI / 8);
                gc.strokeArc(x - r * 2.24, y - r * 2.24, r * 4.48, r * 4.48,
                        sweep - 7, 14, ArcType.OPEN);
            }
            gc.setFill(GOLD);
            gc.fillOval(x - r * .44, y - r * .44, r * .88, r * .88);
            gc.setStroke(NIGHT_DEEP);
            gc.setLineWidth(1.7 * s);
            gc.strokeOval(x - r * .74, y - r * .74, r * 1.48, r * 1.48);
        } else {
            gc.setFill(device.getType() == DeviceType.REMOTE_SERVER ? VIOLET : CYAN);
            gc.fillOval(x - r * .32, y - r * .32, r * .64, r * .64);
            gc.strokeArc(x - r * .66, y - r * .66, r * 1.32, r * 1.32, 25, 210, ArcType.OPEN);
        }
    }

    private void halo(GraphicsContext gc, double x, double y, double radius, Color color, double scale) {
        gc.setStroke(color.deriveColor(0, 1, 1, .75));
        gc.setLineWidth(1.2 * scale);
        gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
    }

    /**
     * Les noms encombraient : écrits sous chaque appareil en permanence, ils
     * recouvraient la carte et lui ôtaient son calme. Seuls les concentrateurs gardent
     * le leur — ils sont peu nombreux et ce sont eux qui situent la carte. Les autres
     * n'apparaissent qu'au survol, à la sélection, ou quand une recherche les désigne.
     */
    private void drawDeviceNames(GraphicsContext gc, Constellation map, Camera camera,
                                 double seconds, double width, double height,
                                 String selectedId, String hoveredId, Filter filter) {
        // Les étiquettes se disputent la place et la première posée garde la sienne :
        // on sert donc d'abord les appareils qui comptent. Trier par distance faisait
        // perdre son nom à la box, pris par quatre adresses anonymes plus proches.
        List<Star> ordered = map.stars().values().stream()
                .sorted(Comparator.comparingDouble((Star star) ->
                        (star.isSystemRoot() ? 1_000_000 : 0) + star.coreRadius()).reversed())
                .toList();

        gc.setTextAlign(TextAlignment.CENTER);
        for (Star star : ordered) {
            String id = star.device().getId();
            boolean named = star.isSystemRoot()
                    || id.equals(selectedId) || id.equals(hoveredId)
                    || (filter.searching() && star.device().getName() != null
                        && star.device().getName().toLowerCase(Locale.ROOT)
                            .contains(filter.query().toLowerCase(Locale.ROOT)));
            if (!named) {
                continue;
            }
            double x = camera.screenX(ConstellationLayout.starX(star, seconds));
            double y = camera.screenY(ConstellationLayout.starY(star, seconds));
            if (x < 0 || x > width || y < 0 || y > height - 60) {
                continue;
            }
            String name = star.device().getName().toUpperCase(Locale.ROOT);
            String address = shortAddress(star.device().getIpAddress());
            double offset = Math.max(star.coreRadius() * camera.zoom(), 13) + 22;
            double boxWidth = Math.max(name.length(), address.length()) * 7.2;
            Box box = new Box(x - boxWidth / 2, y + offset - 12, boxWidth, 32);
            if (labelBoxes.stream().anyMatch(box::hits)) {
                continue;
            }
            labelBoxes.add(box);

            gc.setFont(TITLE_FONT);
            gc.setFill(TEXT);
            gc.fillText(name, x, y + offset);
            gc.setFont(SMALL_FONT);
            gc.setFill(TEXT_DIM);
            gc.fillText(address, x, y + offset + 15);
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private static String shortAddress(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        if (!address.contains(":")) {
            return address;
        }
        String[] parts = address.split(":");
        if (parts.length < 3) {
            return address;
        }
        return "…:" + parts[parts.length - 2] + ':' + parts[parts.length - 1];
    }

    /** Le moniteur : ce qui est affiché, sur ce qui existe, famille par famille. */
    private void drawMonitor(GraphicsContext gc, double width, Filter filter, double detail) {
        double x = width - 216;
        double y = 74;
        gc.setFont(SMALL_FONT);
        gc.setFill(TEXT_DIM);
        gc.fillText(detail < PLAIN_DOT_BELOW ? "MONITEUR · CORTÈGE SIMPLIFIÉ" : "MONITEUR", x, y);

        SatelliteKind[] kinds = { SatelliteKind.ADAPTER, SatelliteKind.PERIPHERAL,
                SatelliteKind.PORT, SatelliteKind.SERVICE };
        String[] names = { "ADAPTATEUR", "PÉRIPHÉRIQUE", "PORT", "SERVICE" };
        for (int i = 0; i < kinds.length; i++) {
            double lineY = y + 20 + i * 18;
            int[] counts = census.getOrDefault(kinds[i], new int[2]);
            boolean shown = filter.showsKind(kinds[i]);

            gc.setFill(shown ? colorFor(kinds[i]) : GOLD_DIM.deriveColor(0, 1, 1, .35));
            gc.fillOval(x, lineY - 5, 7, 7);
            gc.setFill(shown ? TEXT_DIM : GOLD_DIM.deriveColor(0, 1, 1, .45));
            gc.fillText(names[i], x + 14, lineY);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(shown ? counts[0] + " / " + counts[1] : "masqué", x + 192, lineY);
            gc.setTextAlign(TextAlignment.LEFT);
        }
        if (filter.searching()) {
            gc.setFill(GOLD);
            gc.fillText("RECHERCHE « " + filter.query().toUpperCase(Locale.ROOT) + " »", x, y + 110);
        }
    }

    private void drawInspector(GraphicsContext gc, Target target, double height) {
        double x = 24;
        double y = height - 104;
        gc.setFill(NIGHT_DEEP.deriveColor(0, 1, 1, .78));
        gc.fillRoundRect(x - 10, y - 21, 430, 56, 8, 8);
        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .55));
        gc.setLineWidth(1);
        gc.strokeLine(x, y + 30, x + 390, y + 30);
        gc.setFont(TITLE_FONT);
        gc.setFill(TEXT);
        gc.fillText(target.title(), x, y);
        gc.setFont(SMALL_FONT);
        gc.setFill(TEXT_DIM);
        gc.fillText(ellipsize(target.detail(), 68), x, y + 18);
    }

    /**
     * Astrolabe de chargement. Il occupe le centre tant que rien n'est encore connu,
     * puis se replie en haut de la carte pendant le balayage et l'identification.
     */
    private void drawLoading(GraphicsContext gc, double width, double height, double seconds,
                             Loading loading, boolean prominent) {
        double x = width / 2;
        double y = prominent ? height * .46 : 91;
        double radius = prominent ? 58 : 31;
        double panelWidth = prominent ? 330 : 290;
        double panelTop = y - radius - 18;
        double panelHeight = radius * 2 + 76;

        gc.save();
        gc.setFill(NIGHT_DEEP.deriveColor(0, 1, 1, prominent ? .76 : .84));
        gc.fillRoundRect(x - panelWidth / 2, panelTop, panelWidth, panelHeight, 12, 12);
        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .35));
        gc.setLineWidth(1);
        gc.strokeLine(x - panelWidth * .38, panelTop + panelHeight - 1,
                x + panelWidth * .38, panelTop + panelHeight - 1);

        double breath = .64 + .36 * (0.5 + 0.5 * Math.sin(seconds * 2.2));
        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .72));
        gc.strokeOval(x - radius, y - radius, radius * 2, radius * 2);
        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .38));
        gc.strokeOval(x - radius * .72, y - radius * .72, radius * 1.44, radius * 1.44);

        // Deux fragments tournent en sens inverse : le système cherche puis recoupe.
        gc.save();
        gc.translate(x, y);
        gc.rotate(seconds * 31);
        gc.setStroke(GOLD.deriveColor(0, 1, 1, .9));
        gc.setLineWidth(prominent ? 1.7 : 1.25);
        gc.strokeArc(-radius * .88, -radius * .88, radius * 1.76, radius * 1.76,
                18, 92, ArcType.OPEN);
        gc.strokeArc(-radius * .88, -radius * .88, radius * 1.76, radius * 1.76,
                198, 48, ArcType.OPEN);
        gc.rotate(-seconds * 73);
        gc.setStroke(CYAN.deriveColor(0, 1, 1, .82));
        gc.strokeArc(-radius * .53, -radius * .53, radius * 1.06, radius * 1.06,
                42, 118, ArcType.OPEN);
        gc.restore();

        // Une aiguille de radar et deux observations, reliées comme une constellation.
        double sweep = seconds * 1.7 - Math.PI / 2;
        double farX = x + Math.cos(sweep) * radius * .66;
        double farY = y + Math.sin(sweep) * radius * .66;
        gc.setStroke(CYAN.deriveColor(0, 1, 1, .24 + .24 * breath));
        gc.setLineWidth(1);
        gc.strokeLine(x, y, farX, farY);

        double firstAngle = seconds * .72;
        double secondAngle = firstAngle + Math.PI * 1.14;
        double firstX = x + Math.cos(firstAngle) * radius * .88;
        double firstY = y + Math.sin(firstAngle) * radius * .55;
        double secondX = x + Math.cos(secondAngle) * radius * .88;
        double secondY = y + Math.sin(secondAngle) * radius * .55;
        gc.setStroke(GOLD_DIM.deriveColor(0, 1, 1, .52));
        gc.strokeLine(firstX, firstY, secondX, secondY);
        double dot = prominent ? 3.8 : 2.8;
        gc.setFill(CYAN.deriveColor(0, 1, 1, breath));
        gc.fillOval(firstX - dot, firstY - dot, dot * 2, dot * 2);
        gc.setFill(GOLD);
        gc.fillOval(secondX - dot, secondY - dot, dot * 2, dot * 2);

        gc.setFill(GOLD);
        double core = prominent ? 5 : 3.5;
        gc.fillOval(x - core, y - core, core * 2, core * 2);
        gc.setStroke(CYAN.deriveColor(0, 1, 1, .45 * breath));
        gc.strokeOval(x - core * 2.2, y - core * 2.2, core * 4.4, core * 4.4);

        // Le balayage possède une mesure réelle ; les autres phases restent fluides.
        gc.setLineWidth(prominent ? 2.2 : 1.8);
        if (loading.determinate()) {
            gc.setStroke(CYAN.deriveColor(0, 1, 1, .9));
            gc.strokeArc(x - radius - 5, y - radius - 5,
                    (radius + 5) * 2, (radius + 5) * 2,
                    90, -360 * loading.progress(), ArcType.OPEN);
        } else {
            gc.save();
            gc.translate(x, y);
            gc.rotate(-seconds * 46);
            gc.setStroke(CYAN.deriveColor(0, 1, 1, .76));
            gc.strokeArc(-radius - 5, -radius - 5,
                    (radius + 5) * 2, (radius + 5) * 2,
                    12, 72, ArcType.OPEN);
            gc.restore();
        }

        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(TITLE_FONT);
        gc.setFill(TEXT);
        gc.fillText(loading.title().toUpperCase(Locale.ROOT), x, y + radius + 25);
        if (!loading.detail().isBlank()) {
            gc.setFont(SMALL_FONT);
            gc.setFill(TEXT_DIM);
            gc.fillText(ellipsize(loading.detail(), 46), x, y + radius + 43);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.restore();
    }

    private void drawHud(GraphicsContext gc, double width, double height, String status) {
        gc.setFont(SMALL_FONT);
        gc.setFill(TEXT_DIM);
        if (status != null) {
            gc.fillText(status.toUpperCase(Locale.ROOT), 24, height - HUD_BOTTOM);
        }
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("SURVOLER : DÉTAILS   ·   CLIQUER : ÉPINGLER   ·   MOLETTE : ZOOM   ·   GLISSER : DÉPLACER",
                width - 24, height - HUD_BOTTOM);
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private Target nearest(double screenX, double screenY) {
        Target best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Target target : targets) {
            double distance = Math.hypot(target.screenX() - screenX, target.screenY() - screenY);
            if (distance < HIT_RADIUS && distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Target find(String id) {
        for (Target target : targets) {
            if (target.id().equals(id)) {
                return target;
            }
        }
        return null;
    }

    private static String deviceDetail(Device device) {
        return device.getIpAddress() + " · " + device.getMacAddress() + " · "
                + device.getEndpoints().size() + " port(s) · "
                + device.getPeripherals().size() + " périphérique(s)";
    }

    private static Color colorFor(SatelliteKind kind) {
        return switch (kind) {
            case ADAPTER -> CYAN;
            case PERIPHERAL -> BLUE;
            case PORT -> GOLD;
            case SERVICE -> VIOLET;
        };
    }

    /** Rayon vrai d'un objet, en unités du monde. */
    private static double worldRadius(SatelliteKind kind) {
        return switch (kind) {
            case ADAPTER -> ADAPTER_R;
            case PERIPHERAL -> PERIPHERAL_R;
            case PORT -> PORT_R;
            case SERVICE -> SERVICE_R;
        };
    }

    private static String ellipsize(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** Montée douce de 0 à 1 entre deux seuils : le détail apparaît sans à-coup. */
    private static double ramp(double value, double from, double to) {
        if (value <= from) {
            return 0;
        }
        if (value >= to) {
            return 1;
        }
        double t = (value - from) / (to - from);
        return t * t * (3 - 2 * t);
    }
}
