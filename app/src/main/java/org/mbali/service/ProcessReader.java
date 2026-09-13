package org.mbali.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.mbali.model.ProcessSnapshot;

/**
 * Lit les processus de la machine via Win32_Process, et les transforme en photos.
 *
 * read() touche au système ; parse() est une fonction pure, testée sans lancer Windows.
 */
final class ProcessReader {

    private static final String SCRIPT = String.join(" ",
            "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;",
            "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,",
            "@{n='CreationDate';e={ if ($_.CreationDate) { $_.CreationDate.ToUniversalTime().ToString('o') } else { '' } }},",
            "WorkingSetSize,KernelModeTime,UserModeTime | ConvertTo-Csv -NoTypeInformation");

    // Constructeur privé : on ne fait jamais de `new ProcessReader()`
    private ProcessReader() {
    }

    static List<ProcessSnapshot> read() {
        return ShellCommand.output(StandardCharsets.UTF_8, Duration.ofSeconds(15),
                        "powershell", "-NoProfile", "-Command", SCRIPT)
                .map(ProcessReader::parse)
                .orElseGet(List::of); // Renvoie une liste vide si la commande échoue
    }

    static List<ProcessSnapshot> parse(String csv) {
        List<ProcessSnapshot> processes = new ArrayList<>();
        String[] lines = csv.split("\\R");

        // La ligne 0 est l'en-tête ; chaque ligne suivante décrit un processus
        for (int i = 1; i < lines.length; i++) {
            // parseCsvLine retire les guillemets et respecte les virgules à l'intérieur
            // d'un champ : un nom comme « Foo, Inc. helper.exe » ne décale plus les colonnes.
            List<String> fields = HardwareInventory.parseCsvLine(lines[i]);
            if (fields.size() < 7) {
                continue; // ligne incomplète
            }

            // Sans PID lisible, la ligne ne décrit rien d'exploitable : on l'ignore et on
            // passe à la suivante, sans exception.
            OptionalLong pid = parseOptionalLong(fields.get(0));
            if (pid.isEmpty()) {
                continue;
            }

            // Chaque conversion risquée vit dans sa propre fonction : une valeur illisible
            // donne un champ vide, jamais une exception qui interromprait toute la lecture.
            processes.add(new ProcessSnapshot(
                    pid.getAsLong(),
                    parseOptionalLong(fields.get(1)),              // ParentProcessId
                    fields.get(2),                                 // Name
                    parseDate(fields.get(3)),                      // CreationDate
                    parseCpuTime(fields.get(5), fields.get(6)),    // KernelModeTime + UserModeTime
                    parseOptionalLong(fields.get(4)),              // WorkingSetSize, en octets
                    Optional.empty()));                            // utilisateur : non lu pour l'instant
        }
        return processes;
    }

    // --- Petites fonctions de conversion : chacune gère sa propre erreur ---

    private static OptionalLong parseOptionalLong(String text) {
        if (text == null || text.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(text.trim());
            // Ni un PID ni une taille en octets ne peuvent être négatifs
            return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static Optional<Instant> parseDate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(text.trim()));
        } catch (DateTimeParseException e) {
            // Date mal formée = champ vide, mais on garde le processus. On ne rattrape que
            // cette exception-là : rattraper Exception masquerait aussi de vrais bugs.
            return Optional.empty();
        }
    }

    private static Optional<Duration> parseCpuTime(String kernelText, String userText) {
        OptionalLong kernel = parseOptionalLong(kernelText);
        OptionalLong user = parseOptionalLong(userText);
        if (kernel.isEmpty() || user.isEmpty()) {
            return Optional.empty();
        }
        try {
            // Win32_Process compte en unités de 100 nanosecondes. addExact et multiplyExact
            // lèvent une exception en cas de dépassement au lieu de rendre un nombre faux.
            long ticks = Math.addExact(kernel.getAsLong(), user.getAsLong());
            return Optional.of(Duration.ofNanos(Math.multiplyExact(ticks, 100L)));
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
    }
}
