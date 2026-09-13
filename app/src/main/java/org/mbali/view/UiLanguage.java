package org.mbali.view;

/** The two languages available in the application interface. */
public enum UiLanguage {
    FRENCH,
    ENGLISH;

    public String text(String french, String english) {
        return this == ENGLISH ? english : french;
    }

    /** Translates scan messages that contain live counts or addresses. */
    public String scanText(String french) {
        if (this == FRENCH || french == null) {
            return french;
        }
        return french
                .replace("Analyse de la machine locale", "Analyzing local machine")
                .replace("Analyse du système", "System analysis")
                .replace("Inventaire de la machine locale", "Local machine inventory")
                .replace("Analyse locale impossible", "Local analysis failed")
                .replace("Cartographie du réseau", "Network mapping")
                .replace("Détection du sous-réseau et de la route par défaut", "Detecting subnet and default route")
                .replace("Balayage du réseau", "Network scan")
                .replace("Balayage réseau", "Network scan")
                .replace("Balayage arrêté", "Scan stopped")
                .replace("Balayage incomplet", "Incomplete scan")
                .replace("Balayage ", "Scanning ")
                .replace("Identification des appareils", "Identifying devices")
                .replace("Identification", "Identification")
                .replace("Voisins IPv6 connus", "Known IPv6 neighbors")
                .replace("adresses examinées", "addresses checked")
                .replace("appareil(s) à nommer", "device(s) to identify")
                .replace("appareil(s) réseau", "network device(s)")
                .replace("appareil(s)", "device(s)")
                .replace("bluetooth actif(s)", "active Bluetooth device(s)")
                .replace("port(s) en écoute", "listening port(s)")
                .replace("périphérique(s)", "peripheral(s)");
    }
}
