package org.mbali.model;

/**
 * Matériel branché sur un appareil : manette, casque, carte Bluetooth, écran...
 * Ni un port réseau ni une carte réseau, d'où un type à part entière.
 */
public record Peripheral(String name, PeripheralKind kind, String hardwareId) {
}
