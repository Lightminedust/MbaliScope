package org.mbali.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NetworkTopology {
    private final Device localHost; // L'ordinateur central (ton PC)
    private final List<Device> devices = new ArrayList<>();
    private final List<NetworkLink> links = new ArrayList<>();

    public NetworkTopology(Device localHost) {
        this.localHost = localHost;
        // L'hôte local fait automatiquement partie de la topologie
        this.devices.add(localHost);
    }

    public void addDevice(Device device) {
        if (!devices.contains(device)) {
            devices.add(device);
        }
    }

    public void addLink(NetworkLink link) {
        if (!links.contains(link)) {
            links.add(link);
        }
    }

    public Device getLocalHost() { return localHost; }
    // Vues en lecture seule : tout ajout passe par addDevice/addLink et leur contrôle de doublons
    public List<Device> getDevices() { return Collections.unmodifiableList(devices); }
    public List<NetworkLink> getLinks() { return Collections.unmodifiableList(links); }
}
