package org.mbali.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * Nom des processus par PID. Windows utilise tasklist avec un repli PowerShell ;
 * Linux et macOS utilisent la sortie portable de ps.
 */
final class ProcessTable {

    private static final Pattern TASKLIST_LINE = Pattern.compile("^\"([^\"]*)\",\"(\\d+)\"");
    private static final Pattern POWERSHELL_LINE = Pattern.compile("^\"(\\d+)\",\"([^\"]*)\"");
    private static final Pattern POSIX_LINE = Pattern.compile("^\\s*(\\d+)\\s+(.+?)\\s*$");

    private static final String POWERSHELL_SCRIPT =
            "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                    + "Get-Process | Select-Object Id,ProcessName | ConvertTo-Csv -NoTypeInformation";

    private ProcessTable() {
    }

    static Map<Integer, String> read() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return ShellCommand.output("ps", "-eo", "pid=,comm=")
                    .map(ProcessTable::parsePosix).orElseGet(Map::of);
        }
        Map<Integer, String> fromTasklist = ShellCommand.output("tasklist", "/fo", "csv", "/nh")
                .map(ProcessTable::parseTasklist)
                .orElseGet(Map::of);
        if (!fromTasklist.isEmpty()) {
            return fromTasklist;
        }
        // Repli plus coûteux (environ 700 ms), mais qui couvre aussi les systèmes
        // où tasklist est absent ou bridé.
        return ShellCommand.output(StandardCharsets.UTF_8,
                        "powershell", "-NoProfile", "-Command", POWERSHELL_SCRIPT)
                .map(ProcessTable::parsePowerShell)
                .orElseGet(Map::of);
    }

    /** Format tasklist : "Code.exe","18096","Console","6","692 128 K" */
    static Map<Integer, String> parseTasklist(String output) {
        Map<Integer, String> processes = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher matcher = TASKLIST_LINE.matcher(line);
            if (matcher.find()) {
                processes.put(Integer.parseInt(matcher.group(2)), trimExtension(matcher.group(1)));
            }
        }
        return processes;
    }

    /** Format PowerShell : "18096","Code" */
    static Map<Integer, String> parsePowerShell(String output) {
        Map<Integer, String> processes = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher matcher = POWERSHELL_LINE.matcher(line);
            if (matcher.find()) {
                processes.put(Integer.parseInt(matcher.group(1)), trimExtension(matcher.group(2)));
            }
        }
        return processes;
    }

    static Map<Integer, String> parsePosix(String output) {
        Map<Integer, String> processes = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher matcher = POSIX_LINE.matcher(line);
            if (matcher.matches()) {
                String command = matcher.group(2).replace('\\', '/');
                processes.put(Integer.parseInt(matcher.group(1)),
                        command.substring(command.lastIndexOf('/') + 1));
            }
        }
        return processes;
    }

    private static String trimExtension(String name) {
        return name.regionMatches(true, name.length() - 4, ".exe", 0, 4)
                ? name.substring(0, name.length() - 4)
                : name;
    }
}
