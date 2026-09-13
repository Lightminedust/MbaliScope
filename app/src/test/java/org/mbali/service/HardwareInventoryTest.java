package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mbali.model.Peripheral;
import org.mbali.model.PeripheralKind;

class HardwareInventoryTest {

    @Test
    void parsesLinuxUsbInventory() {
        var devices = HardwareInventory.parseLsusb(
                "Bus 001 Device 004: ID 046d:c534 Logitech USB Receiver\n");
        assertEquals(1, devices.size());
        assertEquals("Logitech USB Receiver", devices.get(0).name());
        assertEquals(PeripheralKind.USB, devices.get(0).kind());
    }

    // Extrait réel de Get-PnpDevice sur la machine de développement
    private static final String CSV = """
            "Class","FriendlyName","InstanceId"
            "HIDClass","HID-compliant game controller","HID\\VID_054C&PID_09CC&MI_03\\7&6E58261&0&0000"
            "MEDIA","Wireless Controller","USB\\VID_054C&PID_09CC&MI_03"
            "Bluetooth","Realtek Wireless Bluetooth Adapter","USB\\VID_0BDA&PID_B85C\\00E04C000001"
            "USB","Intel(R) USB 3.20 eXtensible Host Controller - 1.20 (Microsoft)","PCI\\VEN_8086"
            "HIDClass","HID-compliant consumer control device","HID\\VID_0001"
            "HIDClass","HID-compliant consumer control device","HID\\VID_0002"
            "System","Nefarius Virtual Gamepad Emulation Bus","ROOT\\VIGEMBUS\\0000"
            "Processor","Intel(R) Core(TM) i7","ACPI\\GENUINEINTEL"
            """;

    @Test
    void findsTheGameController() {
        List<Peripheral> peripherals = HardwareInventory.parse(CSV);

        assertTrue(peripherals.stream()
                .anyMatch(p -> p.kind() == PeripheralKind.CONTROLLER
                        && p.name().equals("HID-compliant game controller")));
    }

    @Test
    void keepsTheControllerEmulationDriverDespiteItsSystemClass() {
        List<Peripheral> peripherals = HardwareInventory.parse(CSV);

        assertTrue(peripherals.stream()
                .anyMatch(p -> p.name().startsWith("Nefarius") && p.kind() == PeripheralKind.CONTROLLER));
    }

    @Test
    void dropsChipsetAndGenericEntries() {
        List<Peripheral> peripherals = HardwareInventory.parse(CSV);
        List<String> names = peripherals.stream().map(Peripheral::name).toList();

        assertFalse(names.contains("Intel(R) Core(TM) i7"), "le processeur n'est pas un périphérique");
        assertFalse(names.stream().anyMatch(n -> n.contains("eXtensible Host Controller")),
                "un contrôleur hôte USB n'est pas un périphérique branché");
        assertFalse(names.contains("HID-compliant consumer control device"),
                "entrée générique que Windows multiplie");
    }

    @Test
    void keepsBluetoothPlumbingAndRemoteDevicesOutOfThePeripherals() {
        // Releve reel : la plomberie de Windows et des ecouteurs appaires il y a quatre
        // mois figuraient comme du materiel branche. Seule la radio est locale.
        String csv = """
                "Class","FriendlyName","InstanceId"
                "Bluetooth","Microsoft Bluetooth Enumerator","BTH\\MS_BTHBRB\\6&18FE9849&0&1"
                "Bluetooth","Bluetooth Device (RFCOMM Protocol TDI)","BTH\\MS_RFCOMM\\6&18FE9849&0&0"
                "MEDIA","TWS","BTHENUM\\{0000110B-0000-1000-8000-00805F9B34FB}_LOCALMFG&005D\\7&CC49CA5&0&112233445566_C00000000"
                "Bluetooth","TWS Avrcp Transport","BTHENUM\\{0000110C-0000-1000-8000-00805F9B34FB}_LOCALMFG&005D\\7&CC49CA5&0&112233445566_C00000000"
                "Bluetooth","TWS","BTHENUM\\DEV_112233445566\\7&CC49CA5&0&BLUETOOTHDEVICE_112233445566"
                "Bluetooth","Realtek Wireless Bluetooth Adapter","USB\\VID_0BDA&PID_B85C\\00E04C000001"
                """;

        List<String> names = HardwareInventory.parse(csv).stream().map(Peripheral::name).toList();

        assertEquals(List.of("Realtek Wireless Bluetooth Adapter"), names);
    }

    @Test
    void classifiesByDeviceClass() {
        assertEquals(PeripheralKind.BLUETOOTH, HardwareInventory.classify("Bluetooth", "Realtek Adapter"));
        assertEquals(PeripheralKind.AUDIO, HardwareInventory.classify("MEDIA", "Wireless Controller"));
        assertEquals(PeripheralKind.INPUT, HardwareInventory.classify("HIDClass", "HID-compliant touch pad"));
        assertEquals(PeripheralKind.DISPLAY, HardwareInventory.classify("Monitor", "Generic PnP Monitor"));
    }

    @Test
    void parsesQuotedCsvFieldsContainingCommas() {
        List<String> fields = HardwareInventory.parseCsvLine("\"MEDIA\",\"Audio, haut-parleurs\",\"ID\\1\"");

        assertEquals(3, fields.size());
        assertEquals("Audio, haut-parleurs", fields.get(1));
    }
}
