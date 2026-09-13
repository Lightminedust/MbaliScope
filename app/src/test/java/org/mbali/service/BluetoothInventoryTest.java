package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.Presence;
import org.mbali.service.BluetoothInventory.BluetoothDevice;
import org.mbali.service.BluetoothInventory.Kind;

class BluetoothInventoryTest {

    // Structure relevee sur une vraie machine : une paire d'ecouteurs exposee sous cinq
    // entrees, plus la plomberie de Windows. Adresses remplacees par des valeurs d'exemple.
    private static final String CSV = """
            "InstanceId","Name","Connected","LastConnected","ClassOfDevice"
            "BTHENUM\\{0000110B-0000-1000-8000-00805F9B34FB}_LOCALMFG&005D\\7&CC49CA5&0&112233445566_C00000000","TWS","","",""
            "BTHENUM\\{0000110C-0000-1000-8000-00805F9B34FB}_LOCALMFG&005D\\7&CC49CA5&0&112233445566_C00000000","TWS Avrcp Transport","","",""
            "BTHENUM\\{0000110E-0000-1000-8000-00805F9B34FB}_LOCALMFG&005D\\7&CC49CA5&0&112233445566_C00000000","TWS Avrcp Transport","","",""
            "BTHENUM\\{0000111E-0000-1000-8000-00805F9B34FB}_LOCALMFG&005D\\7&CC49CA5&0&112233445566_C00000000","TWS Hands-Free AG","","",""
            "BTHENUM\\DEV_112233445566\\7&CC49CA5&0&BLUETOOTHDEVICE_112233445566","TWS","False","2026-05-09","2360324"
            """;

    @Test
    void fiveWindowsEntriesAreOnePairOfEarbuds() {
        List<BluetoothDevice> devices = BluetoothInventory.parse(CSV);

        assertEquals(1, devices.size(), "cinq entrees, une seule paire d'ecouteurs");
        assertEquals("TWS", devices.get(0).name());
        assertEquals("11:22:33:44:55:66", devices.get(0).formattedAddress());
    }

    @Test
    void presentIsNotConnected() {
        // Le defaut que cette lecture corrige : Windows declarait ces ecouteurs presents
        // et en etat OK, alors que leur derniere connexion datait de quatre mois.
        BluetoothDevice earbuds = BluetoothInventory.parse(CSV).get(0);

        assertEquals(Boolean.FALSE, earbuds.connected());
        assertEquals(LocalDate.of(2026, 5, 9), earbuds.lastConnected());
        assertTrue(earbuds.describe().contains("appairé, non connecté"));
        assertTrue(earbuds.describe().contains("9 mai 2026"));
    }

    @Test
    void theServicesSayWhatTheDeviceIs() {
        BluetoothDevice earbuds = BluetoothInventory.parse(CSV).get(0);

        assertEquals(Kind.AUDIO, earbuds.kind(), "reception audio et mains-libres : un casque");
    }

    @Test
    void withoutServicesTheClassOfDeviceDecides() {
        // Categorie majeure dans les bits 8 a 12 : 2 = telephone, 5 = peripherique de saisie
        assertEquals(Kind.PHONE, BluetoothInventory.kindOf(Set.of(), Integer.toString(2 << 8)));
        assertEquals(Kind.INPUT, BluetoothInventory.kindOf(Set.of(), Integer.toString(5 << 8)));
        assertEquals(Kind.OTHER, BluetoothInventory.kindOf(Set.of(), ""));
    }

    @Test
    void anUnknownConnectionStateIsNotInventedAsConnected() {
        String csv = """
                "InstanceId","Name","Connected","LastConnected","ClassOfDevice"
                "BTHLE\\DEV_AABBCCDDEEFF\\7&1&0&AABBCCDDEEFF","Capteur","","",""
                """;
        BluetoothDevice sensor = BluetoothInventory.parse(csv).get(0);

        assertNull(sensor.connected(), "Windows n'a rien dit : on ne l'invente pas");
        assertTrue(sensor.lowEnergy());
        assertTrue(sensor.describe().contains("état de connexion inconnu"));
    }

    @Test
    void onlyAnAffirmedConnectionMakesADeviceActive() {
        BluetoothDevice disconnected = BluetoothInventory.parse(CSV).get(0);
        BluetoothDevice connected = new BluetoothDevice("AABBCCDDEEFF", "Clavier", Kind.INPUT,
                Boolean.TRUE, null, false);
        BluetoothDevice unknown = new BluetoothDevice("AABBCCDDEE00", "Capteur", Kind.OTHER,
                null, null, true);

        Device earbuds = SystemScanner.toDevice(disconnected);
        assertEquals(DeviceType.BLUETOOTH, earbuds.getType());
        assertEquals(Presence.DORMANT, earbuds.getPresence(), "appaire mais eteint");
        assertEquals(Presence.ACTIVE, SystemScanner.toDevice(connected).getPresence());
        assertEquals(Presence.DORMANT, SystemScanner.toDevice(unknown).getPresence(),
                "un etat inconnu n'est pas une connexion");
        assertFalse(earbuds.getEvidence().isBlank());
    }
}
