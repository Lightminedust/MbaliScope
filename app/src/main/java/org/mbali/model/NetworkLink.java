package org.mbali.model;

// Un record fournit equals/hashCode sur (source, target, protocol) :
// NetworkTopology peut ainsi repérer une liaison déjà connue.
public record NetworkLink(Device source, Device target, String protocol) { // protocol : TCP, UDP
}
