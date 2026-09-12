package org.mbali.model;

public enum AdapterKind {
    WIFI("Wi-Fi"),
    ETHERNET("Ethernet"),
    VIRTUAL("VPN / Virtuel"), // Hyper-V, WSL, VPN, ponts de conteneurs
    OTHER("Autre");

    private final String label;

    AdapterKind(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}
