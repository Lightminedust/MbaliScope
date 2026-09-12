package org.mbali.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NetworkTopologyTest {

    private final Device pc = new Device("local-host", "PC", "192.168.1.64", DeviceType.LOCAL_HOST);

    private static Device router() {
        return new Device("router-1", "Box Routeur", "192.168.1.1", DeviceType.GATEWAY_ROUTER);
    }

    @Test
    void rescannedDeviceIsNotDuplicated() {
        NetworkTopology topology = new NetworkTopology(pc);
        topology.addDevice(router());
        topology.addDevice(router()); // nouvel objet, même id : comme après un second scan
        assertEquals(2, topology.getDevices().size());
    }

    @Test
    void identicalLinkIsNotDuplicated() {
        NetworkTopology topology = new NetworkTopology(pc);
        topology.addLink(new NetworkLink(pc, router(), "TCP"));
        topology.addLink(new NetworkLink(pc, router(), "TCP"));
        assertEquals(1, topology.getLinks().size());
    }

    @Test
    void devicesListIsReadOnly() {
        NetworkTopology topology = new NetworkTopology(pc);
        assertThrows(UnsupportedOperationException.class, () -> topology.getDevices().add(router()));
    }
}
