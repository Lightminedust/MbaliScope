package org.mbali.model;

/**
 * Ce que l'on peut affirmer de l'activité d'un appareil au moment du scan.
 *
 * Connaître un appareil et le voir actif sont deux choses distinctes. Des écouteurs
 * appairés restent déclarés « présents » par Windows alors que leur dernière connexion
 * date de plusieurs mois : les dessiner comme un appareil actif affirmerait une liaison
 * qui n'existe pas.
 */
public enum Presence {
    ACTIVE("Actif"),
    DORMANT("Connu, mais pas actif en ce moment");

    private final String label;

    Presence(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}
