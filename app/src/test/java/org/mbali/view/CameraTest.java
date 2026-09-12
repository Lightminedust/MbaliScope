package org.mbali.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CameraTest {

    // La camera glisse desormais vers sa cible : les invariants de cadrage se
    // verifient donc a l arrivee, en appelant settle(), et le glissement lui-meme
    // fait l objet de ses propres tests.

    @Test
    void screenAndWorldCoordinatesRoundTrip() {
        Camera camera = new Camera();
        camera.pan(120, -40);
        camera.zoomAt(300, 300, 1.5);
        camera.settle();

        assertEquals(742.0, camera.screenX(camera.worldX(742.0)), 1e-9);
        assertEquals(-31.0, camera.screenY(camera.worldY(-31.0)), 1e-9);
    }

    @Test
    void zoomingKeepsThePointUnderTheCursorStill() {
        Camera camera = new Camera();
        double worldXBefore = camera.worldX(400);
        double worldYBefore = camera.worldY(250);

        camera.zoomAt(400, 250, 2.0);
        camera.settle();

        assertEquals(worldXBefore, camera.worldX(400), 1e-9, "le point vise ne doit pas glisser");
        assertEquals(worldYBefore, camera.worldY(250), 1e-9);
    }

    @Test
    void zoomStaysWithinItsLimits() {
        Camera camera = new Camera();
        for (int i = 0; i < 200; i++) {
            camera.zoomAt(0, 0, 2);
        }
        camera.settle();
        assertEquals(Camera.MAX_ZOOM, camera.zoom(), 1e-9);

        for (int i = 0; i < 400; i++) {
            camera.zoomAt(0, 0, 0.5);
        }
        camera.settle();
        assertEquals(Camera.MIN_ZOOM, camera.zoom(), 1e-9);
    }

    @Test
    void fitCentersTheRequestedArea() {
        Camera camera = new Camera();
        camera.fit(-1000, -500, 1000, 500, 800, 600, 50);
        camera.settle();

        assertEquals(400, camera.screenX(0), 1e-9);
        assertEquals(300, camera.screenY(0), 1e-9);
        assertTrue(camera.screenX(1000) <= 800, "le bord droit deborde");
        assertTrue(camera.screenY(500) <= 600, "le bord bas deborde");
    }

    @Test
    void centerOnPutsThePointExactlyInTheMiddle() {
        Camera camera = new Camera();
        camera.pan(333, -77);

        camera.centerOn(0, 0, 500, 1000, 800, 60);
        camera.settle();

        assertEquals(500, camera.screenX(0), 1e-9, "le point vise doit rester au centre");
        assertEquals(400, camera.screenY(0), 1e-9);
        // Le rayon demande doit tenir dans la plus petite dimension utile
        assertTrue(camera.screenY(500) <= 800, "le systeme deborde en hauteur");
    }

    @Test
    void theViewGlidesInsteadOfJumping() {
        Camera camera = new Camera();
        camera.zoomAt(400, 300, 4);

        // Rien n a bouge tant qu aucune image n a ete calculee
        assertEquals(1, camera.zoom(), 1e-9);
        assertFalse(camera.settled(), "une cible a ete posee, la vue doit avoir du chemin");

        camera.update(0.016);
        assertTrue(camera.zoom() > 1, "la vue doit avancer vers sa cible");
        assertTrue(camera.zoom() < camera.targetZoom(), "elle ne doit pas y sauter d un coup");
    }

    @Test
    void theGlideConvergesAndThenStops() {
        Camera camera = new Camera();
        camera.centerOn(1_000, 500, 400, 800, 600, 40);

        for (int frame = 0; frame < 300; frame++) {
            camera.update(0.016);
        }

        assertTrue(camera.settled(), "le mouvement doit finir par s arreter");
        assertEquals(400, camera.screenX(1_000), 1e-6);
        assertEquals(300, camera.screenY(500), 1e-6);
    }

    @Test
    void theGlideDoesNotDependOnTheFrameRate() {
        Camera slow = new Camera();
        Camera fast = new Camera();
        slow.zoomAt(0, 0, 8);
        fast.zoomAt(0, 0, 8);

        slow.update(0.1);
        for (int frame = 0; frame < 10; frame++) {
            fast.update(0.01);
        }

        // Meme temps ecoule, meme avancement : sinon la carte irait plus vite sur une
        // machine rapide, ce qui serait un defaut et non une qualite.
        assertEquals(slow.zoom(), fast.zoom(), 1e-9);
    }

    @Test
    void draggingFollowsTheCursorWithoutLag() {
        Camera camera = new Camera();
        camera.pan(50, 30);

        // Le deplacement emmene la vue et la cible : rien ne revient en arriere
        assertTrue(camera.settled(), "le glissement de la souris doit etre immediat");
        assertEquals(50, camera.offsetX(), 1e-9);
        camera.update(0.5);
        assertEquals(50, camera.offsetX(), 1e-9, "la vue ne doit pas reculer apres le geste");
    }
}
