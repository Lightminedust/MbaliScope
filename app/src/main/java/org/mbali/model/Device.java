package org.mbali.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Device {
    public static final String UNKNOWN_MAC = "Inconnue";

    private final String id;
    private final String name;
    private final String ipAddress;
    private final DeviceType type;
    private String macAddress = UNKNOWN_MAC;
    private Presence presence = Presence.ACTIVE;
    // Ce qui justifie ce que la carte dit de l'appareil : d'où vient son nom, pourquoi il
    // manque, ou dans quel état se trouve sa liaison.
    private String evidence = "";
    private final List<PortEndpoint> endpoints = new ArrayList<>();
    private final List<NetworkAdapter> adapters = new ArrayList<>();
    private final List<Peripheral> peripherals = new ArrayList<>();

    public Device(String id, String name, String ipAddress, DeviceType type) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.ipAddress = ipAddress;
        this.type = type;
    }

    public void addEndpoint(PortEndpoint endpoint) {
        endpoints.add(endpoint);
    }

    public void addAdapter(NetworkAdapter adapter) {
        adapters.add(adapter);
    }

    public void addPeripheral(Peripheral peripheral) {
        peripherals.add(peripheral);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getIpAddress() { return ipAddress; }
    public String getMacAddress() { return macAddress; }
    public DeviceType getType() { return type; }
    public List<PortEndpoint> getEndpoints() { return Collections.unmodifiableList(endpoints); }
    public List<NetworkAdapter> getAdapters() { return Collections.unmodifiableList(adapters); }
    public List<Peripheral> getPeripherals() { return Collections.unmodifiableList(peripherals); }

    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public Presence getPresence() { return presence; }
    public void setPresence(Presence presence) {
        this.presence = presence == null ? Presence.ACTIVE : presence;
    }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence == null ? "" : evidence; }

    // Deux objets Device désignent le même appareil s'ils ont le même id : c'est ce qui
    // empêche NetworkTopology de dupliquer un appareil recréé à chaque scan.
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Device other && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
