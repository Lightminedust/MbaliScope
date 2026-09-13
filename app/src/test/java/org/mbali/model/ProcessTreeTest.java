package org.mbali.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class ProcessTreeTest {

    // Les dates des cas « noyau » reprennent celles mesurees sur une vraie machine.
    private static final Instant BOOT = Instant.parse("2026-09-13T05:58:00Z");

    /** Un processus dont le demarrage est connu, exprime en temps ecoule depuis BOOT. */
    private static ProcessSnapshot process(long pid, long parentPid, String name, Duration afterBoot) {
        return new ProcessSnapshot(pid, OptionalLong.of(parentPid), name,
                Optional.ofNullable(afterBoot).map(BOOT::plus), Optional.empty(),
                OptionalLong.empty(), Optional.empty());
    }

    private static ProcessSnapshot process(long pid, long parentPid, String name, long secondsAfterBoot) {
        return process(pid, parentPid, name, Duration.ofSeconds(secondsAfterBoot));
    }

    @Test
    void aSimpleFamily() {
        ProcessSnapshot chrome = process(100, 1, "chrome.exe", 10);
        ProcessSnapshot renderer = process(101, 100, "chrome.exe", 12);
        ProcessSnapshot gpu = process(102, 100, "chrome.exe", 13);

        ProcessTree tree = new ProcessTree(List.of(chrome, renderer, gpu));

        assertEquals(List.of(chrome), tree.roots(), "le parent 1 est absent : chrome est une racine");
        assertEquals(List.of(renderer, gpu), tree.children(chrome));
        assertEquals(Optional.of(chrome), tree.parentOf(renderer));
    }

    @Test
    void anOrphanBecomesARoot() {
        // Mesure reelle : explorer.exe pointe vers un parent termine au demarrage
        ProcessSnapshot explorer = process(5000, 4200, "explorer.exe", 30);

        ProcessTree tree = new ProcessTree(List.of(explorer));

        assertEquals(List.of(explorer), tree.roots());
        assertTrue(tree.parentOf(explorer).isEmpty());
    }

    @Test
    void idleIsItsOwnParentWithoutLooping() {
        // Mesure reelle : System Idle Process (PID 0) est son propre parent
        ProcessSnapshot idle = process(0, 0, "System Idle Process", 0);
        ProcessSnapshot system = process(4, 0, "System", 5);

        ProcessTree tree = new ProcessTree(List.of(idle, system));

        assertEquals(List.of(idle), tree.roots(), "PID 0 est une racine, pas son propre enfant");
        assertEquals(List.of(system), tree.children(idle));
        assertTrue(tree.parentOf(idle).isEmpty());
    }

    @Test
    void aReusedParentPidIsRejected() {
        // Le parent d'origine est mort ; le PID 200 appartient maintenant a un processus
        // lance APRES l'enfant : ce n'est pas son parent.
        ProcessSnapshot child = process(300, 200, "worker.exe", 10);
        ProcessSnapshot newcomer = process(200, 1, "notepad.exe", 50);

        ProcessTree tree = new ProcessTree(List.of(child, newcomer));

        assertTrue(tree.roots().contains(child), "l'enfant devient une racine");
        assertTrue(tree.children(newcomer).isEmpty(), "le nouveau venu n'a pas adopte l'enfant");
        assertTrue(tree.parentOf(child).isEmpty());
    }

    @Test
    void theKernelKeepsItsChildrenDespiteInvertedDates() {
        // Mesure reelle : System est date a 05:58:05.269, Registry a 05:58:00.579.
        // La regle naive « le parent doit etre plus ancien » l'aurait detache a tort.
        ProcessSnapshot system = process(4, 0, "System", Duration.ofMillis(5_269));
        ProcessSnapshot registry = process(232, 4, "Registry", Duration.ofMillis(579));

        ProcessTree tree = new ProcessTree(List.of(system, registry));

        assertEquals(List.of(registry), tree.children(system));
        assertEquals(Optional.of(system), tree.parentOf(registry));
    }

    @Test
    void anUnknownStartDateKeepsTheLink() {
        // On ne rejette un lien que si on peut prouver qu'il est faux
        ProcessSnapshot parent = process(100, 1, "service.exe", (Duration) null);
        ProcessSnapshot child = process(101, 100, "helper.exe", 20);

        ProcessTree tree = new ProcessTree(List.of(parent, child));

        assertEquals(List.of(child), tree.children(parent));
    }

    @Test
    void theTreeCannotBeModifiedFromOutside() {
        ProcessSnapshot parent = process(100, 1, "chrome.exe", 10);
        ProcessSnapshot child = process(101, 100, "chrome.exe", 12);
        ProcessSnapshot leaf = process(102, 101, "chrome.exe", 14);
        ProcessTree tree = new ProcessTree(List.of(parent, child, leaf));

        assertThrows(UnsupportedOperationException.class, () -> tree.roots().add(leaf));
        assertThrows(UnsupportedOperationException.class, () -> tree.children(parent).add(leaf));
        // Un processus sans enfant renvoie aussi une liste vide non modifiable
        assertTrue(tree.children(leaf).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> tree.children(leaf).add(parent));
    }
}
