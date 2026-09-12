package org.mbali;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkLink;
import org.mbali.model.NetworkTopology;
import org.mbali.service.NetworkScanner;
import org.mbali.service.NetworkScanner.Subnet;
import org.mbali.service.SystemScanner;
import org.mbali.view.Camera;
import org.mbali.view.ConstellationLayout;
import org.mbali.view.ConstellationLayout.Constellation;
import org.mbali.view.ConstellationLayout.SatelliteKind;
import org.mbali.view.ConstellationRenderer;
import org.mbali.view.ConstellationRenderer.Filter;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class App extends Application {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 780;
    private static final double FIT_MARGIN = 70;

    private static final String CONTROL_STYLE =
            "-fx-background-color: rgba(8,11,23,.88); -fx-border-color: #8d7650; "
            + "-fx-text-fill: #e4cda2; -fx-font-family: 'Consolas'; -fx-font-size: 10px; "
            + "-fx-padding: 7 12 7 12; -fx-cursor: hand;";
    private static final String ACTIVE_STYLE =
            "-fx-background-color: rgba(228,205,162,.16); -fx-border-color: #e4cda2; "
            + "-fx-text-fill: #f0e6d2; -fx-font-family: 'Consolas'; -fx-font-size: 10px; "
            + "-fx-padding: 7 12 7 12; -fx-cursor: hand;";
    private static final String FIELD_STYLE =
            "-fx-background-color: rgba(8,11,23,.88); -fx-border-color: #8d7650; "
            + "-fx-text-fill: #f0e6d2; -fx-prompt-text-fill: #6d6250; "
            + "-fx-font-family: 'Consolas'; -fx-font-size: 10px; -fx-padding: 6 10 6 10;";

    private final Camera camera = new Camera();
    private final ConstellationRenderer renderer = new ConstellationRenderer();

    private Canvas canvas;
    private Button refreshButton;
    private Button cancelButton;
    private Thread scanThread;
    private AtomicBoolean scanCancelled = new AtomicBoolean();
    private String selectedId;

    /** Ce que le moniteur laisse voir : familles cochées et recherche en cours. */
    private final EnumSet<SatelliteKind> visibleKinds = EnumSet.allOf(SatelliteKind.class);
    private String query = "";

    // Tous ces champs sont écrits et lus uniquement sur le thread JavaFX : les rappels
    // du scan repassent par Platform.runLater, donc aucune synchronisation n'est nécessaire.
    private Device localHost;
    private final List<Device> discovered = new ArrayList<>();
    private Constellation constellation;
    private String statusText = "Analyse de la machine locale...";

    private double mouseX;
    private double mouseY;
    private boolean mouseInside;
    private double dragX;
    private double dragY;
    private boolean cameraMoved;

    @Override
    public void start(Stage primaryStage) {
        Pane root = new Pane();
        canvas = new Canvas(WIDTH, HEIGHT);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        root.getChildren().add(canvas);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        installControls();

        long startNanos = System.nanoTime();
        AnimationTimer timer = new AnimationTimer() {
            private long previousNanos = startNanos;

            @Override
            public void handle(long now) {
                double seconds = (now - startNanos) / 1_000_000_000.0;
                // La caméra glisse vers sa cible sur le temps réellement écoulé. Le
                // plafond évite un saut après une pause du système, où l'écart entre
                // deux images peut valoir plusieurs secondes.
                camera.update(Math.min((now - previousNanos) / 1_000_000_000.0, 0.1));
                previousNanos = now;
                renderer.draw(gc, constellation, camera,
                        canvas.getWidth(), canvas.getHeight(), seconds,
                        mouseX, mouseY, mouseInside, statusText, selectedId,
                        new Filter(query, visibleKinds));
            }
        };
        timer.start();

        primaryStage.setTitle("Mbaliscope - Constellation Réseau");
        primaryStage.setScene(new Scene(root, WIDTH, HEIGHT));
        installMonitor(root);
        primaryStage.show();
        primaryStage.setOnCloseRequest(event -> cancelScan(false));
        refreshScan();
    }

    /** Le moniteur : balayage, recherche, et un interrupteur par famille. */
    private void installMonitor(Pane root) {
        refreshButton = new Button("↻  ACTUALISER");
        cancelButton = new Button("■  ARRÊTER");
        refreshButton.setStyle(CONTROL_STYLE);
        cancelButton.setStyle(CONTROL_STYLE);
        refreshButton.setOnAction(event -> refreshScan());
        cancelButton.setOnAction(event -> cancelScan(true));
        HBox scanRow = new HBox(8, refreshButton, cancelButton);

        TextField search = new TextField();
        search.setPromptText("RECHERCHER  ( port, processus, matériel… )");
        search.setStyle(FIELD_STYLE);
        search.setPrefWidth(320);
        // Recherche à la frappe : pas de bouton à presser
        search.textProperty().addListener((observable, before, after) -> query = after);
        search.setOnAction(event -> query = search.getText());

        HBox familyRow = new HBox(6,
                familyToggle("ADAPTATEURS", SatelliteKind.ADAPTER),
                familyToggle("PÉRIPHÉRIQUES", SatelliteKind.PERIPHERAL),
                familyToggle("PORTS", SatelliteKind.PORT),
                familyToggle("SERVICES", SatelliteKind.SERVICE));

        VBox monitor = new VBox(8, scanRow, search, familyRow);
        monitor.setLayoutX(24);
        monitor.setLayoutY(20);
        root.getChildren().add(monitor);
    }

    private ToggleButton familyToggle(String label, SatelliteKind kind) {
        ToggleButton toggle = new ToggleButton(label);
        toggle.setSelected(true);
        toggle.setStyle(ACTIVE_STYLE);
        toggle.setOnAction(event -> {
            if (toggle.isSelected()) {
                visibleKinds.add(kind);
                toggle.setStyle(ACTIVE_STYLE);
            } else {
                visibleKinds.remove(kind);
                toggle.setStyle(CONTROL_STYLE);
            }
        });
        return toggle;
    }

    private void installControls() {
        canvas.setOnScroll(event -> {
            camera.zoomAt(event.getX(), event.getY(), event.getDeltaY() > 0 ? 1.18 : 1 / 1.18);
            cameraMoved = true;
        });
        canvas.setOnMousePressed(event -> {
            dragX = event.getX();
            dragY = event.getY();
        });
        canvas.setOnMouseDragged(event -> {
            camera.pan(event.getX() - dragX, event.getY() - dragY);
            dragX = event.getX();
            dragY = event.getY();
            mouseX = event.getX();
            mouseY = event.getY();
            cameraMoved = true;
        });
        canvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
            mouseInside = true;
        });
        canvas.setOnMouseExited(event -> mouseInside = false);

        // Double-clic : retour au cadrage d'ensemble, machine locale au centre
        canvas.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                selectedId = null;
                cameraMoved = false;
                frameWholeSystem();
            } else if (constellation != null) {
                selectedId = renderer.hitTest(constellation, camera, event.getX(), event.getY());
            }
        });

        canvas.widthProperty().addListener((observable, before, after) -> frameIfUntouched());
        canvas.heightProperty().addListener((observable, before, after) -> frameIfUntouched());
    }

    private void refreshScan() {
        cancelScan(false);
        AtomicBoolean token = new AtomicBoolean();
        scanCancelled = token;
        selectedId = null;
        statusText = "Analyse de la machine locale…";
        refreshButton.setDisable(true);
        cancelButton.setDisable(false);
        scanThread = Thread.ofVirtual().name("scan-localhost").start(() -> {
            try {
                Device scanned = SystemScanner.scanLocalHost();
                if (token.get()) return;
                Platform.runLater(() -> {
                    localHost = scanned;
                    discovered.clear();
                    rebuild();
                    scanNetwork(token);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> finishScan(token, "Analyse locale impossible : " + e.getMessage()));
            }
        });
    }

    /** Balaye le réseau local et fait apparaître chaque appareil dès qu'il est trouvé. */
    private void scanNetwork(AtomicBoolean token) {
        scanThread = Thread.ofVirtual().name("scan-lan").start(() -> {
            try {
                Subnet subnet;
                try {
                    subnet = NetworkScanner.detect();
                } catch (IOException noIpv4) {
                    NetworkScanner.scanKnownNeighbors(localHost.getIpAddress(),
                            device -> Platform.runLater(() -> publishDevice(token, device)));
                    Platform.runLater(() -> finishScan(token, "Voisins IPv6 connus · " + summary()));
                    return;
                }
                int total = (int) NetworkScanner.hostAddresses(subnet).stream()
                        .filter(ip -> !ip.equals(subnet.localAddress())).count();
                int progressStep = Math.max(1, total / 400);
                Platform.runLater(() -> statusText = "Balayage " + subnet.localAddress() + "/"
                        + subnet.prefixLength() + " : 0/" + total);

                NetworkScanner.scan(subnet,
                        device -> Platform.runLater(() -> publishDevice(token, device)),
                        done -> {
                            if (done % progressStep == 0 || done == total) {
                                Platform.runLater(() -> {
                                    if (!token.get()) {
                                        statusText = "Balayage réseau : " + done + "/" + total
                                                + "     " + discovered.size() + " appareil(s)";
                                    }
                                });
                            }
                        }, token::get);

                // Le nommage vient après le balayage : la carte est déjà peuplée et ne
                // fait que se préciser, sans retarder l'apparition des appareils.
                if (!token.get()) {
                    // discovered n'appartient qu'au thread JavaFX : on en fait prendre
                    // la copie par ce thread, au lieu de le lire depuis celui du scan.
                    CompletableFuture<List<Device>> snapshot = new CompletableFuture<>();
                    Platform.runLater(() -> {
                        statusText = "Identification des appareils…";
                        snapshot.complete(new ArrayList<>(discovered));
                    });
                    // Le paramètre attendu ici est la passerelle, et non l'adresse
                    // locale : lui passer la nôtre trompait le résolveur, qui n'a
                    // jusqu'ici rien manqué que parce que la passerelle porte déjà le
                    // bon type. On lui donne l'adresse réellement observée.
                    List<Device> known = snapshot.join();
                    String gateway = known.stream()
                            .filter(device -> device.getType() == DeviceType.GATEWAY_ROUTER)
                            .map(Device::getIpAddress).findFirst().orElse(null);
                    NetworkScanner.resolveNames(known, gateway,
                            named -> Platform.runLater(() -> publishDevice(token, named)),
                            token::get);
                }

                Platform.runLater(() -> finishScan(token, token.get() ? "Balayage arrêté" : summary()));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> finishScan(token, "Balayage incomplet : " + e.getMessage()));
                e.printStackTrace();
            }
        });
    }

    private void publishDevice(AtomicBoolean token, Device device) {
        if (token.get()) return;
        int index = discovered.indexOf(device);
        if (index < 0) discovered.add(device);
        else discovered.set(index, device);
        rebuild();
    }

    private void cancelScan(boolean showStatus) {
        scanCancelled.set(true);
        if (scanThread != null) scanThread.interrupt();
        if (showStatus) finishScan("Balayage arrêté");
    }

    private void finishScan(String text) {
        statusText = text;
        if (refreshButton != null) refreshButton.setDisable(false);
        if (cancelButton != null) cancelButton.setDisable(true);
    }

    private void finishScan(AtomicBoolean token, String text) {
        if (scanCancelled == token) finishScan(text);
    }

    private String summary() {
        int ports = localHost == null ? 0 : localHost.getEndpoints().size();
        int hardware = localHost == null ? 0 : localHost.getPeripherals().size();
        // On compte les machines physiques, pas les adresses : sinon le total
        // contredit la carte, qui fusionne les adresses d'un même matériel.
        return machines().size() + " appareil(s) connecté(s)     "
                + ports + " port(s) en écoute     " + hardware + " périphérique(s)";
    }

    /**
     * Les machines physiques à représenter : les adresses d'un même matériel fondues
     * en un appareil, et les adresses de cette machine écartées pour qu'elle ne
     * figure pas deux fois.
     */
    private List<Device> machines() {
        return NetworkScanner.withoutLocalHost(
                NetworkScanner.mergeByHardware(discovered), localHost);
    }

    /**
     * Reconstruit la topologie entière à chaque découverte. C'est peu coûteux à cette
     * échelle, et cela garde la composition cohérente quel que soit l'ordre d'arrivée
     * des appareils.
     */
    private void rebuild() {
        if (localHost == null) {
            return;
        }
        NetworkTopology rebuilt = new NetworkTopology(localHost);
        List<Device> machines = machines();

        // Le concentrateur du système local est la passerelle dès que le système la
        // connaît : les appareils du réseau passent par elle, et non par cette
        // machine, qui n'en est qu'un client de plus. Sans passerelle identifiée, on
        // ne peut rien affirmer d'autre que « vu depuis ici ».
        Device hub = machines.stream()
                .filter(device -> device.getType() == DeviceType.GATEWAY_ROUTER)
                .findFirst().orElse(localHost);

        if (!hub.equals(localHost)) {
            rebuilt.addDevice(hub);
            rebuilt.addLink(new NetworkLink(hub, localHost, "Sortie réseau"));
        }
        for (Device device : machines) {
            if (device.equals(hub) || device.equals(localHost)) {
                continue;
            }
            rebuilt.addDevice(device);
            rebuilt.addLink(new NetworkLink(hub, device, relation(device)));
        }
        constellation = ConstellationLayout.compute(rebuilt);
        frameIfUntouched();
    }

    /** Ce que l'on sait du lien entre un appareil et son concentrateur. */
    private static String relation(Device device) {
        if (device.getEndpoints().isEmpty()) {
            return "Voisin réseau";
        }
        return "TCP · " + device.getEndpoints().stream()
                .map(port -> Integer.toString(port.getPortNumber()))
                .collect(Collectors.joining(", "));
    }

    private void frameIfUntouched() {
        if (!cameraMoved) {
            frameWholeSystem();
        }
    }

    /**
     * L'origine du monde est le concentrateur du premier système — la box quand elle
     * est connue, cette machine sinon. La cadrer au centre avec le rayon total montre
     * tout le réseau d'un coup, autour de ce qui le relie réellement.
     */
    private void frameWholeSystem() {
        if (constellation == null) {
            return;
        }
        double width = canvas.getWidth() > 0 ? canvas.getWidth() : WIDTH;
        double height = canvas.getHeight() > 0 ? canvas.getHeight() : HEIGHT;
        camera.centerOn(0, 0, constellation.totalRadius(), width, height, FIT_MARGIN);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
