package org.mbali.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.mbali.service.PortScanner.PortStatus;

/**
 * Le battement : ce que le réseau fait pendant qu'on le regarde.
 *
 * Deux mesures réelles, répétées, et rien de simulé :
 *   — le temps que met une connexion TCP à aboutir vers la passerelle ;
 *   — le nombre de voisins que le système connaît à l'instant présent, relu dans la
 *     table ARP/NDP, ce qui ne coûte aucun paquet.
 *
 * Quand la passerelle filtre le port sondé, on le dit au lieu d'afficher un chiffre :
 * un « injoignable » honnête vaut mieux qu'une latence inventée. La durée renvoyée est
 * alors celle de l'attente avant abandon, qui ne mesure que notre propre délai.
 */
public final class LivePulse {

    /** Un relevé. latencyMs vaut -1 quand la passerelle n'a pas répondu. */
    public record Sample(long atMillis, int latencyMs, int neighbours, boolean reachable) {
    }

    /** Port sondé pour la mesure : celui qu'une box expose le plus souvent. */
    private static final int PROBE_PORT = 80;
    private static final int PROBE_TIMEOUT_MS = 700;

    private final AtomicBoolean stopped = new AtomicBoolean();
    private Thread thread;

    /**
     * Démarre le battement. Le fournisseur de passerelle est relu à chaque tour : elle
     * n'est connue qu'après le premier balayage, et peut changer d'un scan à l'autre.
     */
    public void start(Supplier<String> gateway, Supplier<String> localAddress,
                      Consumer<Sample> onSample, long periodMs) {
        stop();
        stopped.set(false);
        thread = Thread.ofVirtual().name("live-pulse").start(() -> {
            while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    onSample.accept(measure(gateway.get(), localAddress.get()));
                    Thread.sleep(periodMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException unstable) {
                    // Un relevé raté ne doit pas tuer le battement : on retente au tour
                    // suivant plutôt que de laisser l'indicateur figé sans explication.
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    public void stop() {
        stopped.set(true);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private static Sample measure(String gateway, String localAddress) {
        int neighbours = countNeighbours(localAddress);
        if (gateway == null || gateway.isBlank()) {
            return new Sample(System.currentTimeMillis(), -1, neighbours, false);
        }
        long start = System.nanoTime();
        PortStatus status = PortScanner.scanPort(gateway, PROBE_PORT, PROBE_TIMEOUT_MS);
        int elapsed = (int) ((System.nanoTime() - start) / 1_000_000);
        // Un refus de connexion est une réponse : la machine est là, et le temps mesuré
        // est bien un aller-retour. Un filtrage, lui, ne mesure que notre patience.
        boolean answered = status == PortStatus.OUVERT || status == PortStatus.FERME;
        return new Sample(System.currentTimeMillis(), answered ? elapsed : -1,
                neighbours, answered);
    }

    private static int countNeighbours(String localAddress) {
        AtomicInteger seen = new AtomicInteger();
        try {
            NetworkScanner.scanKnownNeighbors(localAddress == null ? "" : localAddress,
                    device -> seen.incrementAndGet());
        } catch (RuntimeException unreadable) {
            return 0;
        }
        return seen.get();
    }
}
