package org.mbali.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Un processus tel qu'il était à un instant donné : une photo, pas un objet vivant.
 *
 * Mesuré sur une vraie machine Windows : sur 285 processus, Java ne lit la date de
 * début, le temps CPU et l'utilisateur que pour 112 d'entre eux, et le parent pour 72.
 * Tout ce qui peut manquer est donc optionnel. Une mémoire inconnue notée 0 fausserait
 * les totaux et placerait le processus « System » en bas du classement.
 *
 * Deux choses n'y figurent volontairement pas :
 *   — le pourcentage CPU, qui se calcule entre deux photos à partir du temps cumulé ;
 *   — les enfants, que l'arbre reconstruit à partir de parentPid.
 */
public record ProcessSnapshot(long pid, OptionalLong parentPid, String name,
                              Optional<Instant> startTime, Optional<Duration> cpuTime,
                              OptionalLong memoryBytes, Optional<String> user) {

    public static final String UNKNOWN_NAME = "Inconnu";

    /** Constructeur compact : il valide, puis Java affecte les champs tout seul. */
    public ProcessSnapshot {
        if (pid < 0) {
            throw new IllegalArgumentException("PID négatif : " + pid);
        }
        // Un Optional qui vaut lui-même null réintroduirait exactement le plantage qu'il
        // sert à éviter : on refuse dès la construction.
        Objects.requireNonNull(parentPid, "parentPid");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(cpuTime, "cpuTime");
        Objects.requireNonNull(memoryBytes, "memoryBytes");
        Objects.requireNonNull(user, "user");

        if (parentPid.isPresent() && parentPid.getAsLong() < 0) {
            throw new IllegalArgumentException("PID parent négatif : " + parentPid.getAsLong());
        }
        if (memoryBytes.isPresent() && memoryBytes.getAsLong() < 0) {
            throw new IllegalArgumentException("Mémoire négative : " + memoryBytes.getAsLong());
        }
        if (cpuTime.isPresent() && cpuTime.get().isNegative()) {
            throw new IllegalArgumentException("Temps CPU négatif : " + cpuTime.get());
        }
        // Un nom manquant reste lisible, comme PortEndpoint.UNKNOWN_OWNER pour les ports.
        name = name == null || name.isBlank() ? UNKNOWN_NAME : name;
    }

    /**
     * Vrai si les deux photos décrivent le même processus.
     *
     * Windows réutilise les PID des processus terminés : un PID identique ne prouve rien.
     * Seule une date de début connue des deux côtés, et identique, le prouve. Si l'une
     * des deux manque, on ne peut rien affirmer — la réponse est donc non.
     */
    public boolean isSameProcessAs(ProcessSnapshot other) {
        return other != null
                && pid == other.pid
                && startTime.isPresent()
                && startTime.equals(other.startTime);
    }
}
