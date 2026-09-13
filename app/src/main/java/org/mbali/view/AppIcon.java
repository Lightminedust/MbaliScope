package org.mbali.view;

import java.awt.Taskbar;
import java.io.IOException;
import java.net.URL;
import java.util.List;

import javax.imageio.ImageIO;

import javafx.scene.image.Image;

/** Charge la marque MbaliScope aux tailles utilisées par la fenêtre et le bureau. */
public final class AppIcon {

    private static final String RESOURCE = "/org/mbali/assets/mbaliscope-icon.png";

    /**
     * Les petites tailles ont leur propre dessin : anneau d'or épais, étoile, point cyan.
     * Mesuré sur la fenêtre : réduit à 32 px, le logo complet, avec ses traits fins sur fond de
     * nuit, n'était plus qu'un disque presque noir, invisible sur la barre des tâches.
     */
    private static final int[] SMALL_SIZES = { 16, 20, 24, 32, 40, 48, 64 };

    private AppIcon() {
    }

    /**
     * Plusieurs résolutions : Windows choisit la plus proche selon l'échelle d'affichage, sans
     * reprendre l'icône générique de Java.
     */
    public static List<Image> allSizes() {
        return List.of(render(256), render(128), render(64), render(48), render(40), render(32),
                render(24), render(20), render(16));
    }

    /**
     * La marque à la taille demandée : le dessin simplifié jusqu'à 64 px, le logo complet au-delà.
     * Chargée synchroniquement.
     */
    public static Image render(int size) {
        for (int small : SMALL_SIZES) {
            if (small >= size) {
                URL resource = resource("/org/mbali/assets/mbaliscope-icon-" + small + ".png");
                return new Image(resource.toExternalForm(), size, size, true, true, false);
            }
        }
        return new Image(resource().toExternalForm(), size, size, true, true, false);
    }

    /**
     * Certains bureaux distinguent l'icône de la fenêtre de celle du processus Java.
     * Quand leur API le permet, on leur donne explicitement la même marque.
     */
    public static void installDesktopIcon() {
        try {
            if (!Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.setIconImage(ImageIO.read(resource()));
            }
        } catch (IOException | RuntimeException unsupportedDesktop) {
            // L'icône du Stage reste active quand le bureau ne propose pas cette API.
        }
    }

    private static URL resource() {
        return resource(RESOURCE);
    }

    private static URL resource(String path) {
        URL resource = AppIcon.class.getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Icône MbaliScope introuvable : " + path);
        }
        return resource;
    }
}
