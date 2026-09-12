package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.mbali.model.AdapterKind;
import org.mbali.model.Device;
import org.mbali.model.DeviceType;

class SystemScannerTest {

    @Test
    void classifiesAdaptersSeenOnWindows() {
        // Libellés relevés sur une vraie machine Windows 11
        assertEquals(AdapterKind.WIFI,
                SystemScanner.classify("wireless_32768", "Realtek RTL8852BE WiFi 6 802.11ax PCIe Adapter"));
        assertEquals(AdapterKind.VIRTUAL,
                SystemScanner.classify("ethernet_32775", "Hyper-V Virtual Ethernet Adapter"));
        assertEquals(AdapterKind.VIRTUAL,
                SystemScanner.classify("ethernet_32777", "Hyper-V Virtual Ethernet Adapter #2"));
        assertEquals(AdapterKind.VIRTUAL,
                SystemScanner.classify("ethernet_32780", "vEthernet (WSL)"));
        assertEquals(AdapterKind.ETHERNET,
                SystemScanner.classify("ethernet_32769", "Intel(R) Ethernet Connection (7) I219-V"));
    }

    @Test
    void classifiesLinuxInterfaceNames() {
        assertEquals(AdapterKind.WIFI, SystemScanner.classify("wlp2s0", "wlp2s0"));
        assertEquals(AdapterKind.ETHERNET, SystemScanner.classify("enp3s0", "enp3s0"));
        assertEquals(AdapterKind.VIRTUAL, SystemScanner.classify("tun0", "tun0"));
        assertEquals(AdapterKind.VIRTUAL, SystemScanner.classify("docker0", "docker0"));
    }

    @Test
    void unknownAdapterIsNotReportedAsWired() {
        assertEquals(AdapterKind.OTHER, SystemScanner.classify("net5", "Mystery Adapter"));
    }

    @Test
    void scanLocalHostAlwaysReturnsTheLocalDevice() {
        Device local = SystemScanner.scanLocalHost();
        assertEquals(DeviceType.LOCAL_HOST, local.getType());
        assertEquals("local-host", local.getId());
        assertNotNull(local.getIpAddress());
    }
}
