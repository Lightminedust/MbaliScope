package org.mbali.model;

/**
 * Carte réseau d'un appareil (Wi-Fi, Ethernet, virtuelle).
 * Volontairement distincte de PortEndpoint : une interface n'est pas un port,
 * elle n'a donc pas besoin d'un numéro de port fictif.
 */
public record NetworkAdapter(String name, String displayName, AdapterKind kind,
                             String ipAddress, String macAddress) {
    /** Compatibilité avec les anciens appelants ; l'adresse peut aussi être IPv6. */
    public String ipv4Address() { return ipAddress; }
}
