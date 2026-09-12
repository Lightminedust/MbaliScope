package org.mbali.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class RoutingTableTest {

    @Test
    void readsTheWindowsRoutingTable() {
        String output = """
                IPv4 Route Table
                ===========================================================================
                Active Routes:
                Network Destination        Netmask          Gateway       Interface  Metric
                          0.0.0.0          0.0.0.0    192.168.1.254     192.168.1.64     55
                        127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
                """;

        assertEquals(Optional.of("192.168.1.254"), RoutingTable.parseDefaultGateway(output));
    }

    @Test
    void readsTheLinuxNetstatFormat() {
        String output = """
                Kernel IP routing table
                Destination     Gateway         Genmask         Flags   MSS Window  irtt Iface
                0.0.0.0         192.168.1.254   0.0.0.0         UG        0 0          0 wlan0
                192.168.1.0     0.0.0.0         255.255.255.0   U         0 0          0 wlan0
                """;

        assertEquals(Optional.of("192.168.1.254"), RoutingTable.parseDefaultGateway(output));
    }

    @Test
    void readsTheIpRouteFormat() {
        String output = "default via 192.168.1.254 dev wlan0 proto dhcp metric 600\n"
                + "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.64\n";

        assertEquals(Optional.of("192.168.1.254"), RoutingTable.parseDefaultGateway(output));
    }

    @Test
    void returnsNothingWhenThereIsNoDefaultRoute() {
        String output = """
                Destination     Gateway         Genmask         Flags Iface
                192.168.1.0     0.0.0.0         255.255.255.0   U     wlan0
                """;

        assertTrue(RoutingTable.parseDefaultGateway(output).isEmpty());
    }
}
