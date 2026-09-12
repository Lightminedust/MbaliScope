package org.mbali.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Exécution d'une commande système courte dont on ne lit que la sortie.
 * Renvoie un Optional vide dès que quelque chose cloche (commande absente sur ce
 * système, code de retour non nul, délai dépassé), pour que l'appelant se rabatte
 * simplement sur une autre source.
 */
final class ShellCommand {

    private static final int TIMEOUT_SECONDS = 5;

    private ShellCommand() {
    }

    /** Lit la sortie dans l'encodage par défaut, qui convient aux commandes ASCII. */
    static Optional<String> output(String... command) {
        return output(Charset.defaultCharset(), command);
    }

    /**
     * Variante à encodage imposé : PowerShell écrit dans la page de codes de la console,
     * qui mutile les noms accentués ou les symboles (Intel® devenait « Intelr »).
     * Les scripts concernés forcent UTF-8 en sortie et se lisent donc en UTF-8.
     */
    static Optional<String> output(Charset charset, String... command) {
        return output(charset, Duration.ofSeconds(TIMEOUT_SECONDS), command);
    }

    static Optional<String> output(Charset charset, Duration timeout, String... command) {
        Process process = null;
        FutureTask<String> reader = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            InputStream stream = process.getInputStream();
            reader = new FutureTask<>(() -> {
                try (InputStream in = stream) {
                    return new String(in.readAllBytes(), charset);
                }
            });
            Thread.ofVirtual().name("command-output").start(reader);
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return Optional.empty();
            }
            String output = reader.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException | ExecutionException | TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (reader != null) {
                reader.cancel(true);
            }
        }
    }
}
