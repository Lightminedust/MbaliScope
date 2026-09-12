package org.mbali.view;

/**
 * Fenêtre de vue sur la constellation : déplacement et zoom.
 * Le monde est en unités logiques, l'écran en pixels ; tout passe par ici.
 *
 * La vue ne saute pas à destination : elle y glisse. Chaque geste fixe une cible, et
 * update() rapproche la vue courante de cette cible à chaque image, d'autant plus vite
 * qu'elle en est loin. C'est ce qui donne le mouvement continu des cartes spatiales,
 * là où un zoom appliqué d'un coup s'arrête net.
 *
 * Le déplacement à la souris fait exception : il emmène la vue ET la cible, pour que
 * la carte reste collée au curseur. Un glissement là serait ressenti comme un retard.
 */
public final class Camera {

    /**
     * L'amplitude de zoom va de la vue d'ensemble du graphe jusqu'à un seul nœud.
     */
    public static final double MIN_ZOOM = 0.0008;
    public static final double MAX_ZOOM = 12;

    /**
     * Constante de temps du glissement : la vue parcourt environ 63 % du chemin
     * restant en autant de secondes. Le calcul se fait sur le temps réellement écoulé,
     * donc le mouvement est le même à 30 ou à 144 images par seconde.
     */
    private static final double GLIDE_SECONDS = 0.38;

    /** En deçà, on colle à la cible : sinon la vue tremblerait indéfiniment. */
    private static final double SETTLED_PIXELS = 0.05;

    private double offsetX;
    private double offsetY;
    private double zoom = 1;

    private double targetOffsetX;
    private double targetOffsetY;
    private double targetZoom = 1;

    public double zoom() { return zoom; }

    public double offsetX() { return offsetX; }

    public double offsetY() { return offsetY; }

    /** Le zoom visé, que la vue n'a pas encore forcément atteint. */
    public double targetZoom() { return targetZoom; }

    /** Vrai quand la vue a rejoint sa cible et ne bouge plus. */
    public boolean settled() {
        return Math.abs(targetOffsetX - offsetX) < SETTLED_PIXELS
                && Math.abs(targetOffsetY - offsetY) < SETTLED_PIXELS
                && Math.abs(targetZoom - zoom) < targetZoom * 1e-4;
    }

    /**
     * Rapproche la vue de sa cible. À appeler une fois par image avec le temps écoulé ;
     * sans cet appel, la caméra reste là où elle est.
     */
    public void update(double deltaSeconds) {
        if (deltaSeconds <= 0) {
            return;
        }
        // Décroissance exponentielle : indépendante de la cadence d'affichage.
        double progress = 1 - Math.exp(-deltaSeconds / GLIDE_SECONDS);
        offsetX += (targetOffsetX - offsetX) * progress;
        offsetY += (targetOffsetY - offsetY) * progress;
        zoom += (targetZoom - zoom) * progress;
        if (settled()) {
            settle();
        }
    }

    /** Amène immédiatement la vue à sa cible, sans glissement. */
    public void settle() {
        offsetX = targetOffsetX;
        offsetY = targetOffsetY;
        zoom = targetZoom;
    }

    /**
     * Déplacement à la souris : la vue suit le curseur sans retard, donc on emmène
     * aussi la cible. Sinon la carte reviendrait en arrière dès le doigt levé.
     */
    public void pan(double deltaScreenX, double deltaScreenY) {
        offsetX += deltaScreenX;
        offsetY += deltaScreenY;
        targetOffsetX += deltaScreenX;
        targetOffsetY += deltaScreenY;
    }

    /**
     * Zoome en gardant fixe le point du monde situé sous le curseur.
     *
     * L'ancrage se lit sur la vue affichée, celle que l'utilisateur regarde, et la
     * cible est posée pour que ce même point retombe sous le curseur à l'arrivée.
     */
    public void zoomAt(double screenX, double screenY, double factor) {
        double anchorWorldX = worldX(screenX);
        double anchorWorldY = worldY(screenY);
        targetZoom = clamp(targetZoom * factor);
        targetOffsetX = screenX - anchorWorldX * targetZoom;
        targetOffsetY = screenY - anchorWorldY * targetZoom;
    }

    /** Cadre la zone demandée au centre de la vue. */
    public void fit(double minX, double minY, double maxX, double maxY,
                    double viewWidth, double viewHeight, double margin) {
        double spanX = Math.max(maxX - minX, 1);
        double spanY = Math.max(maxY - minY, 1);
        double usableWidth = Math.max(viewWidth - 2 * margin, 1);
        double usableHeight = Math.max(viewHeight - 2 * margin, 1);

        targetZoom = clamp(Math.min(usableWidth / spanX, usableHeight / spanY));
        targetOffsetX = viewWidth / 2 - (minX + maxX) / 2 * targetZoom;
        targetOffsetY = viewHeight / 2 - (minY + maxY) / 2 * targetZoom;
    }

    /**
     * Place un point du monde au centre exact de la vue, en zoomant pour que le
     * rayon demandé y tienne.
     */
    public void centerOn(double worldX, double worldY, double radius,
                         double viewWidth, double viewHeight, double margin) {
        double usableWidth = Math.max(viewWidth - 2 * margin, 1);
        double usableHeight = Math.max(viewHeight - 2 * margin, 1);

        targetZoom = clamp(Math.min(usableWidth, usableHeight) / Math.max(radius * 2, 1));
        targetOffsetX = viewWidth / 2 - worldX * targetZoom;
        targetOffsetY = viewHeight / 2 - worldY * targetZoom;
    }

    public double worldX(double screenX) { return (screenX - offsetX) / zoom; }

    public double worldY(double screenY) { return (screenY - offsetY) / zoom; }

    public double screenX(double worldX) { return worldX * zoom + offsetX; }

    public double screenY(double worldY) { return worldY * zoom + offsetY; }

    private static double clamp(double value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }
}
