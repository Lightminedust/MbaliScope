package org.mbali.view;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.mbali.model.AdapterKind;
import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkAdapter;
import org.mbali.model.NetworkLink;
import org.mbali.model.NetworkTopology;
import org.mbali.model.Peripheral;
import org.mbali.model.PeripheralKind;
import org.mbali.model.PortEndpoint;
import org.mbali.model.PortType;
import org.mbali.model.Presence;
import org.mbali.view.ConstellationLayout.Constellation;
import org.mbali.view.ConstellationRenderer.Filter;
import org.mbali.view.ConstellationRenderer.Loading;
import org.mbali.view.ConstellationRenderer.Pulse;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;

/**
 * Capture reproductible de la carte du réseau, sans balayage. Le réseau est inventé : adresses
 * privées d'exemple, adresses MAC locales fictives, aucun nom de machine réel.
 */
public final class ConstellationPreview {
    public static void main(String[] args) throws Exception {
        Constellation map = ConstellationLayout.compute(fixture());
        Path directory = Path.of("build", "previews");
        Files.createDirectories(directory);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                double width = 1440, height = 1000;
                Canvas canvas = new Canvas(width, height);
                new Scene(new Pane(canvas));
                ConstellationRenderer renderer = new ConstellationRenderer();
                renderer.setPulse(new Pulse(14, 9, 2_000, new double[] { 12, 18, 11, 25, 14, 9, 16, 14, 30, 12, 14 }));
                Camera camera = new Camera();
                camera.centerOn(0, 0, map.totalRadius(), width, height, 70);
                camera.settle();
                String status = "SCÉNARIO DE VÉRIFICATION";
                Filter all = Filter.all();

                long start = 0;
                for (int i = 0; i < 240; i++) {
                    if (i == 60) start = System.nanoTime();
                    renderer.draw(canvas.getGraphicsContext2D(), map, camera, width, height, 20 + i / 60.0,
                            0, 0, false, status, null, all, null);
                    if (i % 15 == 0) canvas.snapshot(null, null);
                }
                canvas.snapshot(null, null);
                System.out.printf("Vue d'ensemble : %.1f ms par image%n", (System.nanoTime() - start) / 180e6);
                capture(canvas, directory.resolve("network-overview.png"));

                Device pc = map.stars().keySet().stream().filter(d -> d.getType() == DeviceType.LOCAL_HOST).findFirst().orElseThrow();
                Device box = map.stars().keySet().stream().filter(d -> d.getType() == DeviceType.GATEWAY_ROUTER).findFirst().orElseThrow();
                var machine = map.stars().get(pc);
                camera.centerOn(ConstellationLayout.starX(machine, 24), ConstellationLayout.starY(machine, 24),
                        machine.systemRadius() * .8, width, height, 60);
                camera.settle();
                start = System.nanoTime();
                for (int i = 0; i < 120; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), map, camera, width, height, 24 + i / 60.0,
                            0, 0, false, status, null, all, null);
                    if (i % 15 == 0) canvas.snapshot(null, null);
                }
                canvas.snapshot(null, null);
                System.out.printf("Cette machine de près : %.1f ms par image%n", (System.nanoTime() - start) / 120e6);
                capture(canvas, directory.resolve("network-host.png"));

                var gateway = map.stars().get(box);
                camera.centerOn(ConstellationLayout.starX(gateway, 26), ConstellationLayout.starY(gateway, 26),
                        gateway.coreRadius() * 4, width, height, 60);
                camera.settle();
                renderer.draw(canvas.getGraphicsContext2D(), map, camera, width, height, 26, 0, 0, false, status, null, all, null);
                capture(canvas, directory.resolve("network-gateway.png"));

                var paired = map.stars().values().stream()
                        .filter(star -> star.device().getType() == DeviceType.BLUETOOTH).findFirst().orElseThrow();
                camera.centerOn(ConstellationLayout.starX(paired, 26), ConstellationLayout.starY(paired, 26),
                        paired.coreRadius() * 9, width, height, 60);
                camera.settle();
                for (int i = 0; i < 10; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), map, camera, width, height, 26 + i / 60.0, 0, 0, false, status, null, all, null);
                }
                capture(canvas, directory.resolve("network-bluetooth.png"));

                camera.centerOn(0, 0, map.totalRadius(), width, height, 70);
                camera.settle();
                for (int i = 0; i < 20; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), map, camera, width, height, 27 + i / 60.0,
                            0, 0, false, status, box.getId(), all, null);
                }
                capture(canvas, directory.resolve("network-dossier-gateway.png"));
                for (int i = 0; i < 20; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), map, camera, width, height, 28 + i / 60.0,
                            0, 0, false, status, pc.getId(), all, null);
                }
                capture(canvas, directory.resolve("network-dossier-host.png"));
                String port = pc.getId() + "/port:443:0.0.0.0:svc-web";
                for (int i = 0; i < 20; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), map, camera, width, height, 29 + i / 60.0,
                            0, 0, false, status, port, all, Loading.progress("Balayage du réseau", "120 / 254 adresses examinées", .47));
                }
                capture(canvas, directory.resolve("network-dossier-port.png"));
                renderer.draw(canvas.getGraphicsContext2D(), null, camera, width, height, 30, 0, 0, false,
                        "Analyse de la machine locale…", null, all, Loading.indeterminate("Analyse du système", "Inventaire de la machine locale"));
                capture(canvas, directory.resolve("network-loading.png"));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        boolean completed = done.await(90, TimeUnit.SECONDS);
        Platform.exit();
        if (!completed) throw new IllegalStateException("Capture expirée");
        if (failure.get() != null) throw new IllegalStateException("Capture impossible", failure.get());
        System.out.println("Captures : " + directory.toAbsolutePath());
    }

    private static NetworkTopology fixture() {
        Device pc = new Device("local-host", "Poste de test", "192.168.1.20", DeviceType.LOCAL_HOST);
        pc.setMacAddress("02:00:00:00:00:20");
        pc.addAdapter(new NetworkAdapter("wlan0", "Wi-Fi d'exemple", AdapterKind.WIFI, "192.168.1.20", "02:00:00:00:00:20"));
        pc.addAdapter(new NetworkAdapter("eth0", "Ethernet d'exemple", AdapterKind.ETHERNET, "10.0.0.20", "02:00:00:00:00:21"));
        pc.addAdapter(new NetworkAdapter("vnet0", "Pont virtuel", AdapterKind.VIRTUAL, "172.20.0.1", "02:00:00:00:00:22"));
        pc.addPeripheral(new Peripheral("Manette d'exemple", PeripheralKind.CONTROLLER, "HID\\EXEMPLE1"));
        pc.addPeripheral(new Peripheral("Casque d'exemple", PeripheralKind.AUDIO, "USB\\EXEMPLE2"));
        pc.addPeripheral(new Peripheral("Écran d'exemple", PeripheralKind.DISPLAY, "DISPLAY\\EXEMPLE3"));
        pc.addPeripheral(new Peripheral("Disque d'exemple", PeripheralKind.STORAGE, "USB\\EXEMPLE4"));
        pc.addEndpoint(new PortEndpoint(443, "HTTPS", PortType.NETWORK_PHYSICAL, "svc-web", "0.0.0.0", "TLS", true));
        int[] numbers = { 135, 139, 445, 5040, 7680, 49664, 49665, 49666, 49667, 49668, 5353, 1900, 3000, 8080, 5432, 27017 };
        for (int number : numbers) {
            pc.addEndpoint(new PortEndpoint(number, "TCP " + number, PortType.NETWORK_PHYSICAL, "svc-" + number, "0.0.0.0"));
        }

        Device box = new Device("192.168.1.1", "Box d'exemple", "192.168.1.1", DeviceType.GATEWAY_ROUTER);
        box.setMacAddress("02:00:00:00:00:01");
        box.addEndpoint(new PortEndpoint(80, "HTTP", PortType.NETWORK_PHYSICAL));
        box.addEndpoint(new PortEndpoint(53, "DNS", PortType.NETWORK_PHYSICAL));

        NetworkTopology topology = new NetworkTopology(pc);
        topology.addDevice(box);
        topology.addLink(new NetworkLink(box, pc, "Sortie réseau"));
        for (int i = 0; i < 9; i++) {
            boolean named = i % 3 == 0;
            Device peer = new Device("192.168.1." + (30 + i), named ? "Appareil nommé " + i : "Appareil " + (30 + i),
                    "192.168.1." + (30 + i), DeviceType.LAN_PEER);
            peer.setMacAddress("02:00:00:00:01:" + String.format("%02d", i));
            for (int p = 0; p < i % 4; p++) peer.addEndpoint(new PortEndpoint(8000 + p, "TCP", PortType.NETWORK_PHYSICAL));
            topology.addDevice(peer);
            topology.addLink(new NetworkLink(box, peer, "Voisin réseau"));
        }
        Device remote = new Device("203.0.113.10", "Serveur d'exemple", "203.0.113.10", DeviceType.REMOTE_SERVER);
        topology.addDevice(remote);
        topology.addLink(new NetworkLink(box, remote, "Distant"));
        for (int i = 0; i < 3; i++) {
            Device paired = new Device("bt:02000000020" + i, "Écouteurs d'exemple " + i, "Bluetooth", DeviceType.BLUETOOTH);
            paired.setMacAddress("02:00:00:00:02:0" + i);
            paired.setPresence(i == 2 ? Presence.DORMANT : Presence.ACTIVE);
            paired.setEvidence(i == 2 ? "Appairé, éteint" : "Connecté");
            topology.addDevice(paired);
            topology.addLink(new NetworkLink(pc, paired, "Bluetooth"));
        }
        return topology;
    }

    private static void capture(Canvas canvas, Path target) throws Exception {
        WritableImage image = canvas.snapshot(null, null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) {
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        ImageIO.write(png, "png", target.toFile());
    }
}
