package org.mbali.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Un arbre de processus, reconstruit à partir d'une liste de photos.
 *
 * Comme des commentaires stockés à plat avec un parent_id : on indexe une seule fois,
 * puis on retrouve en un accès le parent et les enfants de chaque processus.
 *
 * Un lien enfant → parent n'est rejeté que s'il est démontrablement faux. Trois pièges
 * ont été mesurés sur une vraie machine Windows (277 processus) :
 *   — System Idle Process (PID 0) est son propre parent : sans garde, boucle infinie ;
 *   — dix orphelins, dont explorer.exe, dont le parent s'est terminé au démarrage ;
 *   — Secure System et Registry sont datés 4,7 s AVANT System, qui est pourtant leur
 *     vrai parent. Les processus du noyau sont donc exemptés de la règle des dates.
 *
 * Aucun ordre n'est garanti : les listes suivent l'ordre d'arrivée. Trier est un choix
 * d'affichage (par nom, par CPU, par mémoire), qui n'a pas sa place dans le modèle.
 */
public class ProcessTree {

    // Processus du noyau : ils ne s'arrêtent jamais pendant une session Windows, leur PID
    // ne peut donc pas avoir été réutilisé. Un lien vers eux est valable quelles que soient
    // les dates, qui sont inversées au démarrage.
    private static final long IDLE_PID = 0;
    private static final long SYSTEM_PID = 4;

    private final Map<Long, ProcessSnapshot> byPid = new HashMap<>();
    private final Map<Long, List<ProcessSnapshot>> childrenByParentPid = new HashMap<>();
    private final List<ProcessSnapshot> roots;

    public ProcessTree(List<ProcessSnapshot> processes) {
        // 1. Indexer tous les processus par leur PID pour un accès instantané
        for (ProcessSnapshot process : processes) {
            byPid.put(process.pid(), process);
        }

        List<ProcessSnapshot> computedRoots = new ArrayList<>();

        // 2. Construire la hiérarchie en un seul passage
        for (ProcessSnapshot child : processes) {
            if (hasValidParent(child)) {
                long parentPid = child.parentPid().getAsLong();
                // Équivalent du (children[id] ??= []).push(child)
                childrenByParentPid.computeIfAbsent(parentPid, key -> new ArrayList<>()).add(child);
            } else {
                computedRoots.add(child);
            }
        }

        // 3. Verrouiller les listes pour empêcher toute modification externe
        this.roots = List.copyOf(computedRoots);
        for (Map.Entry<Long, List<ProcessSnapshot>> entry : childrenByParentPid.entrySet()) {
            entry.setValue(List.copyOf(entry.getValue())); // copie figée à la place de la liste modifiable
        }
    }

    /**
     * Applique les 4 règles pour déterminer si le lien parent-enfant est valable.
     */
    private boolean hasValidParent(ProcessSnapshot child) {
        // Règle 1 : il faut un PID parent connu
        if (child.parentPid().isEmpty()) {
            return false;
        }
        long parentPid = child.parentPid().getAsLong();

        // Règle 2 : empêcher les boucles infinies (System Idle Process est son propre parent)
        if (parentPid == child.pid()) {
            return false;
        }

        // Règle 3 : le parent doit exister dans le relevé actuel (orphelin sinon)
        ProcessSnapshot parent = byPid.get(parentPid);
        if (parent == null) {
            return false;
        }

        // Règle 4 : le parent est un processus du noyau, OU il a démarré avant ou en même
        // temps que l'enfant
        if (parentPid == IDLE_PID || parentPid == SYSTEM_PID) {
            return true;
        }
        if (parent.startTime().isPresent() && child.startTime().isPresent()) {
            // Le parent ne doit pas avoir démarré après l'enfant
            return !parent.startTime().get().isAfter(child.startTime().get());
        }

        // Si l'une des dates est inconnue, on ne peut rien réfuter : on garde le lien
        return true;
    }

    public List<ProcessSnapshot> roots() {
        return roots;
    }

    public List<ProcessSnapshot> children(ProcessSnapshot parent) {
        // Renvoie une liste vide non modifiable si aucun enfant n'est trouvé
        return childrenByParentPid.getOrDefault(parent.pid(), List.of());
    }

    public Optional<ProcessSnapshot> parentOf(ProcessSnapshot child) {
        if (hasValidParent(child)) {
            return Optional.of(byPid.get(child.parentPid().getAsLong()));
        }
        return Optional.empty();
    }
}
