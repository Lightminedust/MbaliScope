package org.mbali.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerspectiveTest {
    @Test
    void projectionAndGroundAreInverseAtEverySupportedInclination() {
        for (double degrees = 65; degrees <= 90; degrees += 5) {
            Perspective view = new Perspective(1400, 900, 5000, -2000, .008, Math.toRadians(degrees));
            for (double x = -100_000; x <= 100_000; x += 20_000) {
                double[] screen = new double[4];
                assertTrue(view.project(x, x * .7, 0, screen));
                double[] ground = view.ground(screen[0], screen[1]);
                assertEquals(x, ground[0], 1e-8);
                assertEquals(x * .7, ground[1], 1e-8);
                assertEquals(.008, screen[2], 1e-12, "même échelle mémoire partout dans la carte");
            }
        }
    }

    @Test
    void zoomAndPanKeepTheGroundPointUnderTheCursor() {
        Camera camera = new Camera();
        camera.centerOn(1000, -3000, 80_000, 1400, 900, 80);
        camera.settle();
        SpacetimeRenderer renderer = new SpacetimeRenderer();
        double[] ground = Perspective.of(camera, 1400, 900, Math.toRadians(72)).ground(980, 220);
        renderer.zoomAt(camera, 980, 220, 1.8, 1400, 900);
        camera.settle();
        double[] screen = new double[4];
        Perspective.of(camera, 1400, 900, Math.toRadians(72)).project(ground[0], ground[1], 0, screen);
        assertEquals(980, screen[0], 1e-8);
        assertEquals(220, screen[1], 1e-8);
        renderer.pan(camera, 120, -70);
        Perspective.of(camera, 1400, 900, Math.toRadians(72)).project(ground[0], ground[1], 0, screen);
        assertEquals(1100, screen[0], 1e-8);
        assertEquals(150, screen[1], 1e-8);
    }
}
