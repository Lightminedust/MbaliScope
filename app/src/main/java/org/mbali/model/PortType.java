package org.mbali.model;

public enum PortType {
    NETWORK_PHYSICAL, // Sockets réseau classiques (Ethernet/Wi-Fi)
    VIRTUAL_TUNNEL,   // Bouclage, VPN, ponts de conteneurs
    HARDWARE_STREAM,  // Flux de périphériques (USB, écouteurs, médias)
    INTERNET_SOCKET   // Connexions web externes
}
