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

    private AppIcon() {
    }

    /**
     * Plusieurs résolutions empêchent Windows et les gestionnaires de fenêtres de
     * reprendre l'icône générique de Java pour les petites représentations.
     */
    public static List<Image> allSizes() {
        return List.of(render(256), render(128), render(64), render(48), render(32), render(16));
    }

    /** Une copie redimensionnée de la même source, chargée synchroniquement. */
    public static Image render(int size) {
        URL resource = resource();
        return new Image(resource.toExternalForm(), size, size, true, true, false);
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
        URL resource = AppIcon.class.getResource(RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Icône MbaliScope introuvable : " + RESOURCE);
        }
        return resource;
    }
}
