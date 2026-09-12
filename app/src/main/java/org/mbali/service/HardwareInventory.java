package org.mbali.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mbali.model.Peripheral;
import org.mbali.model.PeripheralKind;

/**
 * Matériel présent sur la machine locale, lu via Get-PnpDevice.
 * Windows déclare plus de 200 entrées, dont la majeure partie est du chipset :
 * on ne retient que ce qu'un utilisateur reconnaîtrait comme un périphérique.
 */
final class HardwareInventory {

    // La première instruction force UTF-8 en sortie, sans quoi les symboles des noms
    // de matériel ressortent abîmés (Intel® devenait « Intelr »).
    private static final String POWERSHELL_SCRIPT =
            "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                    + "Get-PnpDevice -PresentOnly | Where-Object { $_.Status -eq 'OK' } "
                    + "| Select-Object Class,FriendlyName,InstanceId | ConvertTo-Csv -NoTypeInformation";

    private static final Pattern CSV_FIELD = Pattern.compile("\"((?:[^\"]|\"\")*)\"");

    /** Manettes, y compris les pilotes d'émulation type ViGEmBus. */
    private static final Pattern CONTROLLER = Pattern.compile(
            ".*(game ?controller|gamepad|manette|joystick|xbox|dualsense|dualshock|virtual gamepad).*");

    /** Entrées génériques que Windows multiplie et qui n'apprennent rien. */
    private static final Pattern GENERIC = Pattern.compile(
            ".*(consumer control device|system controller|vendor-defined device|wireless radio controls"
                    + "|usb input device|i2c hid device|input configuration device|root hub"
                    + "|host controller|composite device|hid event filter"
                    + "|portable device control device|streaming service proxy).*");

    private HardwareInventory() {
    }

    static List<Peripheral> read() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            if (os.contains("mac")) {
                return ShellCommand.output("system_profiler", "SPUSBDataType", "SPAudioDataType",
                                "SPDisplaysDataType", "-detailLevel", "mini")
                        .map(HardwareInventory::parseSystemProfiler).orElseGet(List::of);
            }
            List<Peripheral> devices = new ArrayList<>();
            ShellCommand.output("lsusb").map(HardwareInventory::parseLsusb).ifPresent(devices::addAll);
            ShellCommand.output("lsblk", "-dn", "-o", "NAME,MODEL,TRAN")
                    .map(HardwareInventory::parseLsblk).ifPresent(devices::addAll);
            return List.copyOf(devices);
        }
        return ShellCommand.output(StandardCharsets.UTF_8,
                        "powershell", "-NoProfile", "-Command", POWERSHELL_SCRIPT)
                .map(HardwareInventory::parse)
                .orElseGet(List::of);
    }

    static List<Peripheral> parseLsusb(String output) {
        List<Peripheral> devices = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Pattern linePattern = Pattern.compile("^Bus \\d+ Device \\d+: ID (\\S+)\\s+(.+)$");
        for (String line : output.split("\\R")) {
            Matcher matcher = linePattern.matcher(line.trim());
            if (matcher.matches() && seen.add(matcher.group(2).toLowerCase(Locale.ROOT))) {
                String name = matcher.group(2).trim();
                devices.add(new Peripheral(name, classifyPortable(name), "USB\\" + matcher.group(1)));
            }
        }
        return devices;
    }

    static List<Peripheral> parseLsblk(String output) {
        List<Peripheral> devices = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String clean = line.trim();
            if (!clean.isEmpty()) {
                String[] fields = clean.split("\\s+", 2);
                String name = fields.length == 2 && !fields[1].isBlank() ? fields[1] : fields[0];
                devices.add(new Peripheral(name.trim(), PeripheralKind.STORAGE, "BLOCK\\" + fields[0]));
            }
        }
        return devices;
    }

    static List<Peripheral> parseSystemProfiler(String output) {
        List<Peripheral> devices = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : output.split("\\R")) {
            String clean = line.trim();
            if (clean.endsWith(":") && !clean.contains("DataType")) {
                String name = clean.substring(0, clean.length() - 1).trim();
                if (!name.isBlank() && seen.add(name.toLowerCase(Locale.ROOT))) {
                    devices.add(new Peripheral(name, classifyPortable(name), "SYSTEM_PROFILER\\" + name));
                }
            }
        }
        return devices;
    }

    private static PeripheralKind classifyPortable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (CONTROLLER.matcher(lower).matches()) return PeripheralKind.CONTROLLER;
        if (lower.matches(".*(audio|headset|speaker|microphone).*")) return PeripheralKind.AUDIO;
        if (lower.matches(".*(keyboard|mouse|touchpad|input).*")) return PeripheralKind.INPUT;
        if (lower.matches(".*(disk|storage|flash|ssd|hdd).*")) return PeripheralKind.STORAGE;
        if (lower.matches(".*(display|monitor|graphics).*")) return PeripheralKind.DISPLAY;
        if (lower.contains("bluetooth")) return PeripheralKind.BLUETOOTH;
        return PeripheralKind.USB;
    }

    static List<Peripheral> parse(String csv) {
        List<Peripheral> peripherals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : csv.split("\\R")) {
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 3 || fields.get(0).equals("Class")) {
                continue;
            }
            String deviceClass = fields.get(0);
            String name = fields.get(1);
            if (name.isBlank() || !isInteresting(deviceClass, name)) {
                continue;
            }
            // Le même intitulé revient parfois une dizaine de fois
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                peripherals.add(new Peripheral(name, classify(deviceClass, name), fields.get(2)));
            }
        }
        return peripherals;
    }

    static boolean isInteresting(String deviceClass, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (CONTROLLER.matcher(lower).matches()) {
            return true; // une manette prime, quelle que soit sa classe
        }
        if (GENERIC.matcher(lower).matches()) {
            return false;
        }
        return switch (deviceClass) {
            case "HIDClass", "Bluetooth", "USB", "MEDIA", "AudioEndpoint",
                 "Monitor", "DiskDrive", "WPD", "Camera", "Image", "Printer",
                 "Mouse", "Keyboard" -> true;
            default -> false;
        };
    }

    static PeripheralKind classify(String deviceClass, String name) {
        if (CONTROLLER.matcher(name.toLowerCase(Locale.ROOT)).matches()) {
            return PeripheralKind.CONTROLLER;
        }
        return switch (deviceClass) {
            case "Bluetooth" -> PeripheralKind.BLUETOOTH;
            case "USB" -> PeripheralKind.USB;
            case "MEDIA", "AudioEndpoint" -> PeripheralKind.AUDIO;
            case "Monitor" -> PeripheralKind.DISPLAY;
            case "DiskDrive", "WPD" -> PeripheralKind.STORAGE;
            case "HIDClass", "Mouse", "Keyboard" -> PeripheralKind.INPUT;
            default -> PeripheralKind.OTHER;
        };
    }

    /** Découpe une ligne CSV produite par ConvertTo-Csv, guillemets doublés compris. */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        Matcher matcher = CSV_FIELD.matcher(line);
        while (matcher.find()) {
            fields.add(matcher.group(1).replace("\"\"", "\""));
        }
        return fields;
    }
}
