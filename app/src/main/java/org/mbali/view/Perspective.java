package org.mbali.view;

/**
 * Projection orthographique inclinée de la nappe. L'échelle reste la même au premier
 * plan et au fond, pour permettre la comparaison des surfaces mémoire.
 *
 * La caméra 2D existante reste la seule source de vérité : elle désigne le point visé sur
 * la nappe et le zoom à cet endroit. Cette classe n'ajoute que l'inclinaison.
 *
 * Repère du monde : x vers la droite, y vers le spectateur, h vers le haut — les puits
 * ont une hauteur négative.
 */
public final class Perspective {

    public static final double MIN_ELEVATION = Math.toRadians(65);
    public static final double MAX_ELEVATION = Math.toRadians(90);

    private final double width;
    private final double height;
    private final double targetX;
    private final double targetY;
    private final double zoom;
    private final double cos;
    private final double sin;

    public Perspective(double width, double height, double targetX, double targetY,
                       double zoom, double elevation) {
        this.width = width;
        this.height = height;
        this.targetX = targetX;
        this.targetY = targetY;
        this.zoom = zoom;
        double clamped = Math.max(MIN_ELEVATION, Math.min(MAX_ELEVATION, elevation));
        this.cos = Math.cos(clamped);
        this.sin = Math.sin(clamped);
    }

    public static Perspective of(Camera camera, double width, double height, double elevation) {
        return new Perspective(width, height, camera.worldX(width / 2), camera.worldY(height / 2),
                camera.zoom(), elevation);
    }

    /**
     * Projette un point du monde. Écrit dans out : x et y à l'écran, puis l'échelle en
     * pixels par unité du monde, puis la profondeur relative au plan visé.
     *
     * @return vrai : une projection orthographique n'a pas de plan proche
     */
    public boolean project(double x, double y, double h, double[] out) {
        double dx = x - targetX;
        double dy = y - targetY;
        double depth = -dy * cos - h * sin;
        double up = -dy * sin + h * cos;
        // Projection orthographique : même quantité de mémoire, même surface à
        // l'écran, y compris au fond de la carte. L'inclinaison révèle la nappe.
        double scale = zoom;
        out[0] = width / 2 + dx * scale;
        out[1] = height / 2 - up * scale;
        out[2] = scale;
        out[3] = depth;
        return true;
    }

    /**
     * Le point de la nappe au repos (h = 0) qui se trouve sous un pixel.
     *
     * @return {x, y} dans le monde
     */
    public double[] ground(double screenX, double screenY) {
        return new double[] { targetX + (screenX - width / 2) / zoom,
                targetY + (screenY - height / 2) / (zoom * sin) };
    }

    public double zoom() { return zoom; }

    public double sin() { return sin; }
}
