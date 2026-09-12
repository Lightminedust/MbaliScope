package org.mbali.model;

public class PortEndpoint {
    public static final String UNKNOWN_OWNER = "Inconnu";

    private final int portNumber;
    private final String name;       // Ex: "HTTP", "DLNA", "USB Audio Stream"
    private final PortType type;
    private final String owner;      // Processus qui détient le port, sur la machine locale
    private final String localAddress;
    private final String serviceEvidence;
    private final boolean serviceVerified;
    private boolean active;

    public PortEndpoint(int portNumber, String name, PortType type) {
        this(portNumber, name, type, UNKNOWN_OWNER);
    }

    public PortEndpoint(int portNumber, String name, PortType type, String owner) {
        this(portNumber, name, type, owner, "");
    }

    public PortEndpoint(int portNumber, String name, PortType type, String owner, String localAddress) {
        this(portNumber, name, type, owner, localAddress, "", false);
    }

    public PortEndpoint(int portNumber, String name, PortType type, String owner, String localAddress,
                        String serviceEvidence, boolean serviceVerified) {
        this.localAddress = localAddress;
        this.serviceEvidence = serviceEvidence;
        this.serviceVerified = serviceVerified;
        this.portNumber = portNumber;
        this.name = name;
        this.type = type;
        this.owner = owner;
        this.active = true;
    }

    public int getPortNumber() { return portNumber; }
    public String getName() { return name; }
    public PortType getType() { return type; }
    public String getOwner() { return owner; }
    public String getLocalAddress() { return localAddress; }
    public String getServiceEvidence() { return serviceEvidence; }
    public boolean isServiceVerified() { return serviceVerified; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
