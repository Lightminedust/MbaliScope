package org.mbali.model;

public enum PeripheralKind {
    CONTROLLER("Manette"),
    INPUT("Périphérique de saisie"),
    AUDIO("Audio"),
    BLUETOOTH("Bluetooth"),
    USB("USB"),
    DISPLAY("Écran"),
    STORAGE("Stockage"),
    OTHER("Autre");

    private final String label;

    PeripheralKind(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}
