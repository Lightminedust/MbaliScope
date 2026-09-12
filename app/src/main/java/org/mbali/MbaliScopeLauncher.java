package org.mbali;

/**
 * Point d'entrée neutre pour les distributions sur classpath.
 *
 * Le lanceur Java traite spécialement une classe principale qui étend directement
 * {@code Application} et cherche alors JavaFX dans les modules du JDK. Les paquets
 * OpenJFX de l'application vivent sur son classpath ; ce relais laisse donc
 * {@link App} initialiser JavaFX avec les bonnes bibliothèques embarquées.
 */
public final class MbaliScopeLauncher {

    private MbaliScopeLauncher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
