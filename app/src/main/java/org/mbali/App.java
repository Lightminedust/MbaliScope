package org.mbali;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.mbali.model.Device;
import org.mbali.model.DeviceType;
import org.mbali.model.NetworkLink;
import org.mbali.model.NetworkTopology;
import org.mbali.model.Presence;
import org.mbali.model.ProcessSnapshot;
import org.mbali.service.LivePulse;
import org.mbali.service.NetworkScanner;
import org.mbali.service.NetworkScanner.Subnet;
import org.mbali.service.SystemScanner;
import org.mbali.view.AppIcon;
import org.mbali.view.Camera;
import org.mbali.view.ConstellationLayout;
import org.mbali.view.ConstellationLayout.Constellation;
import org.mbali.view.ConstellationLayout.SatelliteKind;
import org.mbali.view.ConstellationRenderer;
import org.mbali.view.ConstellationRenderer.Filter;
import org.mbali.view.ConstellationRenderer.Loading;
import org.mbali.view.SpacetimeLayout;
import org.mbali.view.SpacetimeRenderer;
import org.mbali.view.UiLanguage;
import org.mbali.view.WindowChrome;

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
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class App extends Application {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 780;
    private static final double FIT_MARGIN = 70;

    // Les deux vues partagent le style de planche : noir, filets gris, capitales, accent rouge
    private static final String PLATE_CONTROL_STYLE =
            "-fx-background-color: rgba(6,6,10,.85); -fx-border-color: #3a3a44; -fx-background-radius: 0; "
            + "-fx-text-fill: #c8c8d2; -fx-font-family: 'Segoe UI'; -fx-font-weight: bold; -fx-font-size: 9.5px; "
            + "-fx-padding: 7 13 7 13; -fx-cursor: hand;";
    private static final String PLATE_ACTIVE_STYLE =
            "-fx-background-color: #ff2d55; -fx-border-color: #ff2d55; -fx-background-radius: 0; "
            + "-fx-text-fill: #06060a; -fx-font-family: 'Segoe UI'; -fx-font-weight: bold; -fx-font-size: 9.5px; "
            + "-fx-padding: 7 13 7 13; -fx-cursor: hand;";
    private static final String PLATE_FIELD_STYLE =
            "-fx-background-color: rgba(6,6,10,.85); -fx-border-color: transparent transparent #5a5a66 transparent; "
            + "-fx-background-radius: 0; -fx-text-fill: #f2f2f5; -fx-prompt-text-fill: #6a6a76; "
            + "-fx-font-family: 'Segoe UI'; -fx-font-size: 10px; -fx-padding: 6 2 6 2;";
    /** Un interrupteur allumé : filet et texte clairs, sans l'aplat rouge réservé à la vue choisie. */
    private static final String PLATE_ON_STYLE =
            "-fx-background-color: rgba(6,6,10,.85); -fx-border-color: #c8c8d2; -fx-background-radius: 0; "
            + "-fx-text-fill: #f2f2f5; -fx-font-family: 'Segoe UI'; -fx-font-weight: bold; -fx-font-size: 9.5px; "
            + "-fx-padding: 7 13 7 13; -fx-cursor: hand;";
    private static final String PLATE_OFF_STYLE =
            "-fx-background-color: rgba(6,6,10,.85); -fx-border-color: #2a2a32; -fx-background-radius: 0; "
            + "-fx-text-fill: #5a5a66; -fx-font-family: 'Segoe UI'; -fx-font-weight: bold; -fx-font-size: 9.5px; "
            + "-fx-padding: 7 13 7 13; -fx-cursor: hand;";

    private final Camera camera = new Camera();
    private final ConstellationRenderer renderer = new ConstellationRenderer();

    /** Les deux systèmes que la fenêtre sait montrer. */
    private enum View { NETWORK, PROCESSES }

    /** Écart entre deux relevés de processus : la lecture elle-même consomme du CPU. */
    private static final long PROCESS_REFRESH_MS = 3_000;

    private View view = View.NETWORK;
    // Chaque vue garde sa caméra : revenir au réseau retrouve le cadrage laissé.
    private final Camera processCamera = new Camera();
    private final SpacetimeRenderer spacetime = new SpacetimeRenderer();
    // Écrit et lu sur le thread graphique ; le relevé est mis en forme ailleurs puis publié ici.
    private SpacetimeLayout.Field processField;
    private boolean processCameraMoved;
    private AtomicBoolean processWatchStopped = new AtomicBoolean(true);
    private Thread processWatch;

    private Button networkButton;
    private Button processesButton;
    private Button languageButton;
    private Button principalViewButton;
    private Button wholeViewButton;
    private Button pauseViewButton;
    private final List<ToggleButton> familyButtons = new ArrayList<>();
    private HBox scanRow;
    private HBox familyRow;
    private HBox processRow;
    private List<Button> processButtons = List.of();
    private boolean processOverview;
    private boolean canvasDragged;
    private String processReadError;
    private TextField search;

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
    private final List<Device> bluetooth = new ArrayList<>();
    private Constellation constellation;
    private String statusText = "Analyse de la machine locale...";
    private Loading loading = Loading.indeterminate(
            "Analyse du système", "Inventaire de la machine locale");

    private WindowChrome chrome;
    private Stage primaryStage;
    private UiLanguage language = UiLanguage.FRENCH;
    private final LivePulse pulse = new LivePulse();
    private final List<Double> latencies = new ArrayList<>();
    private static final int LATENCY_HISTORY = 40;

    // Lus par le thread du battement, écrits par le thread graphique : d'où les
    // références atomiques plutôt qu'un champ ordinaire.
    private final AtomicReference<String> liveGateway = new AtomicReference<>();
    private final AtomicReference<String> liveLocalAddress = new AtomicReference<>("");

    private double mouseX;
    private double mouseY;
    private boolean mouseInside;
    private double dragX;
    private double dragY;
    private boolean cameraMoved;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        Pane root = new Pane();
        canvas = new Canvas(WIDTH, HEIGHT);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        root.getChildren().add(canvas);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        installControls();

        // Sans décoration système, la fenêtre porte ses propres commandes ; en
        // contrepartie, déplacement et agrandissement sont refaits à la main.
        primaryStage.initStyle(StageStyle.UNDECORATED);
        chrome = new WindowChrome(primaryStage);

        long startNanos = System.nanoTime();
        AnimationTimer timer = new AnimationTimer() {
            private long previousNanos = startNanos;

            @Override
            public void handle(long now) {
                double seconds = (now - startNanos) / 1_000_000_000.0;
                // La caméra glisse vers sa cible sur le temps réellement écoulé. Le
                // plafond évite un saut après une pause du système, où l'écart entre
                // deux images peut valoir plusieurs secondes.
                double elapsed = Math.min((now - previousNanos) / 1_000_000_000.0, 0.1);
                previousNanos = now;
                camera.update(elapsed);
                processCamera.update(elapsed);
                if (view == View.PROCESSES) {
                    spacetime.draw(gc, processField, processCamera,
                            canvas.getWidth(), canvas.getHeight(), seconds, elapsed,
                            mouseX, mouseY, mouseInside, selectedId, query, processStatus());
                } else {
                    renderer.draw(gc, constellation, camera,
                            canvas.getWidth(), canvas.getHeight(), seconds,
                            mouseX, mouseY, mouseInside, language.scanText(statusText), selectedId,
                            new Filter(query, visibleKinds), localized(loading));
                }
            }
        };
        timer.start();

        primaryStage.setTitle("MbaliScope - Constellation Réseau");
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        // Le fond de scène évite l'éclair blanc entre l'ouverture et la première image
        scene.setFill(Color.web("#040407"));
        primaryStage.setScene(scene);

        // La même marque est fournie à toutes les tailles utiles afin que la fenêtre,
        // la barre des tâches et le sélecteur d'applications restent cohérents.
        primaryStage.getIcons().setAll(AppIcon.allSizes());
        AppIcon.installDesktopIcon();

        installMonitor(root);
        updateLanguage();
        installChrome(root);
        chrome.fillScreen();
        primaryStage.show();
        primaryStage.setOnCloseRequest(event -> shutdown());
        refreshScan();
        startLivePulse();
        // Option de lancement : ouvrir directement sur l'espace-temps des processus,
        // par exemple pour une démonstration (gradlew run --args="--processes")
        if (getParameters().getRaw().contains("--processes")) {
            setView(View.PROCESSES);
        }
    }

    /** La barre de fenêtre : zone de déplacement à gauche, commandes à droite. */
    private void installChrome(Pane root) {
        Pane bar = chrome.titleBar();
        HBox buttons = chrome.buttons();
        buttons.layoutXProperty().bind(root.widthProperty().subtract(buttons.widthProperty()).subtract(8));
        buttons.setLayoutY(0);
        root.getChildren().addAll(bar, buttons);
    }

    /**
     * Le battement : une mesure réelle toutes les six secondes, publiée sur le thread
     * graphique. La passerelle est relue à chaque tour, car elle n'est connue qu'après
     * le premier balayage.
     */
    private void startLivePulse() {
        pulse.start(liveGateway::get,
                () -> localHost == null ? "" : liveLocalAddress.get(),
                sample -> Platform.runLater(() -> publishPulse(sample)),
                6_000);
    }

    private void publishPulse(LivePulse.Sample sample) {
        latencies.add(sample.latencyMs() < 0 ? 0 : (double) sample.latencyMs());
        while (latencies.size() > LATENCY_HISTORY) {
            latencies.remove(0);
        }
        double[] history = new double[latencies.size()];
        for (int i = 0; i < history.length; i++) {
            history[i] = latencies.get(i);
        }
        renderer.setPulse(new ConstellationRenderer.Pulse(
                sample.latencyMs(), sample.neighbours(),
                System.currentTimeMillis() - sample.atMillis(), history));
    }

    private void shutdown() {
        pulse.stop();
        stopProcessWatch();
        cancelScan(false);
    }

    /** Bascule entre la carte du réseau et l'espace-temps des processus. */
    private void setView(View next) {
        if (view == next) {
            return;
        }
        view = next;
        selectedId = null;
        boolean processes = next == View.PROCESSES;
        networkButton.setStyle(processes ? PLATE_CONTROL_STYLE : PLATE_ACTIVE_STYLE);
        processesButton.setStyle(processes ? PLATE_ACTIVE_STYLE : PLATE_CONTROL_STYLE);
        // Le balayage réseau et les familles de satellites n'ont pas de sens pour les processus
        scanRow.setVisible(!processes);
        scanRow.setManaged(!processes);
        familyRow.setVisible(!processes);
        familyRow.setManaged(!processes);
        processRow.setVisible(processes);
        processRow.setManaged(processes);
        updateSearchPrompt();
        // On ne lit les processus que lorsqu'on les regarde : la lecture a elle-même un coût
        if (processes) {
            startProcessWatch();
        } else {
            stopProcessWatch();
        }
    }

    /**
     * Relève les processus à intervalle régulier, sur un thread virtuel. La mise en forme
     * est faite sur ce thread aussi, puis publiée d'un bloc au thread graphique : l'animation
     * ne s'arrête jamais le temps d'un relevé.
     */
    private void startProcessWatch() {
        stopProcessWatch();
        AtomicBoolean stopped = new AtomicBoolean();
        processWatchStopped = stopped;
        OptionalLong ownPid = OptionalLong.of(ProcessHandle.current().pid());
        int cores = Runtime.getRuntime().availableProcessors();
        SpacetimeLayout.Field seed = processField;
        processWatch = Thread.ofVirtual().name("process-watch").start(() -> {
            SpacetimeLayout.Field previous = seed;
            while (!stopped.get()) {
                List<ProcessSnapshot> processes = SystemScanner.scanProcesses();
                if (stopped.get()) {
                    return;
                }
                if (processes.isEmpty()) {
                    Platform.runLater(() -> {
                        if (!stopped.get()) processReadError = "Lecture indisponible · dernier relevé conservé";
                    });
                } else {
                    SpacetimeLayout.Field field = SpacetimeLayout.compute(processes,
                            SystemScanner.totalMemoryBytes(), cores, Instant.now(), ownPid, previous);
                    previous = field;
                    Platform.runLater(() -> {
                        if (!stopped.get()) {
                            processReadError = null;
                            publishProcesses(field);
                        }
                    });
                }
                try {
                    Thread.sleep(PROCESS_REFRESH_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        });
    }

    private void stopProcessWatch() {
        processWatchStopped.set(true);
        if (processWatch != null) {
            processWatch.interrupt();
            processWatch = null;
        }
    }

    private void publishProcesses(SpacetimeLayout.Field field) {
        boolean first = processField == null;
        processField = field;
        if (first && !processCameraMoved) {
            frameProcesses();
            processCamera.settle();
        }
    }

    private void frameProcesses() {
        if (processField == null) {
            return;
        }
        double width = canvas.getWidth() > 0 ? canvas.getWidth() : WIDTH;
        double height = canvas.getHeight() > 0 ? canvas.getHeight() : HEIGHT;
        spacetime.frame(processCamera, processField, width, height, processOverview);
    }

    private String processStatus() {
        if (processReadError != null) return language.text(processReadError, "Reading unavailable · keeping last sample");
        if (spacetime.paused()) return language.text(
                "Vue figée · les relevés continuent en arrière-plan",
                "Frozen view · sampling continues in the background");
        if (processField == null) {
            return language.text("Lecture des processus…", "Reading processes…");
        }
        if (processField.masses().isEmpty()) {
            return language.text("Aucun processus lu : la lecture a échoué", "No processes read: sampling failed");
        }
        return language.text(
                processField.processCount() + " processus     " + processField.masses().size()
                        + " familles     actualisation ≈ " + PROCESS_REFRESH_MS / 1000 + " s",
                processField.processCount() + " processes     " + processField.masses().size()
                        + " families     refresh ≈ " + PROCESS_REFRESH_MS / 1000 + " s");
    }

    private void markCameraMoved() {
        if (view == View.PROCESSES) {
            processCameraMoved = true;
        } else {
            cameraMoved = true;
        }
    }

    /** Le moniteur : balayage, recherche, et un interrupteur par famille. */
    private void installMonitor(Pane root) {
        refreshButton = new Button("↻  ACTUALISER");
        cancelButton = new Button("■  ARRÊTER");
        refreshButton.setStyle(PLATE_CONTROL_STYLE);
        cancelButton.setStyle(PLATE_CONTROL_STYLE);
        refreshButton.setOnAction(event -> refreshScan());
        cancelButton.setOnAction(event -> cancelScan(true));
        scanRow = new HBox(8, refreshButton, cancelButton);

        // Les deux systèmes : la carte du réseau, et l'espace-temps des processus
        networkButton = new Button("RÉSEAU");
        processesButton = new Button("PROCESSUS");
        networkButton.setStyle(PLATE_ACTIVE_STYLE);
        processesButton.setStyle(PLATE_CONTROL_STYLE);
        networkButton.setOnAction(event -> setView(View.NETWORK));
        processesButton.setOnAction(event -> setView(View.PROCESSES));
        languageButton = new Button();
        languageButton.setStyle(PLATE_CONTROL_STYLE);
        languageButton.setOnAction(event -> {
            language = language == UiLanguage.FRENCH ? UiLanguage.ENGLISH : UiLanguage.FRENCH;
            updateLanguage();
        });
        HBox viewRow = new HBox(6, networkButton, processesButton, languageButton);

        search = new TextField();
        search.setPromptText("RECHERCHER  ( port, processus, matériel… )");
        search.setStyle(PLATE_FIELD_STYLE);
        search.setPrefWidth(320);
        // Recherche à la frappe : pas de bouton à presser
        search.textProperty().addListener((observable, before, after) -> query = after);
        search.setOnAction(event -> {
            query = search.getText();
            if (view == View.PROCESSES && spacetime.focus(processCamera, processField, query,
                    canvas.getWidth(), canvas.getHeight())) processCameraMoved = true;
        });

        familyButtons.clear();
        familyRow = new HBox(6,
                familyToggle(SatelliteKind.ADAPTER),
                familyToggle(SatelliteKind.PERIPHERAL),
                familyToggle(SatelliteKind.PORT),
                familyToggle(SatelliteKind.SERVICE));

        principalViewButton = new Button("PRINCIPAUX");
        wholeViewButton = new Button("VUE D’ENSEMBLE");
        pauseViewButton = new Button("Ⅱ  PAUSE");
        processButtons = List.of(principalViewButton, wholeViewButton, pauseViewButton);
        for (Button button : processButtons) button.setStyle(PLATE_CONTROL_STYLE);
        principalViewButton.setOnAction(event -> {
            processOverview = false;
            processCameraMoved = false;
            frameProcesses();
        });
        wholeViewButton.setOnAction(event -> {
            processOverview = true;
            processCameraMoved = false;
            frameProcesses();
        });
        pauseViewButton.setOnAction(event -> {
            spacetime.setPaused(!spacetime.paused());
            updateLanguage();
        });
        processRow = new HBox(6, principalViewButton, wholeViewButton, pauseViewButton);
        processRow.setVisible(false);
        processRow.setManaged(false);
        VBox monitor = new VBox(8, viewRow, scanRow, search, familyRow, processRow);
        monitor.setLayoutX(24);
        // Sous la barre de fenêtre, qui occupe désormais le haut
        monitor.setLayoutY(WindowChrome.BAR_HEIGHT + 14);
        root.getChildren().add(monitor);
    }

    private ToggleButton familyToggle(SatelliteKind kind) {
        ToggleButton toggle = new ToggleButton();
        toggle.setUserData(kind);
        familyButtons.add(toggle);
        toggle.setSelected(true);
        toggle.setStyle(PLATE_ON_STYLE);
        toggle.setOnAction(event -> {
            if (toggle.isSelected()) {
                visibleKinds.add(kind);
                toggle.setStyle(PLATE_ON_STYLE);
            } else {
                visibleKinds.remove(kind);
                toggle.setStyle(PLATE_OFF_STYLE);
            }
        });
        return toggle;
    }

    private void updateLanguage() {
        renderer.setLanguage(language);
        spacetime.setLanguage(language);
        if (primaryStage != null) {
            primaryStage.setTitle(language.text(
                    "MbaliScope - Constellation Réseau",
                    "MbaliScope - Network Constellation"));
        }
        if (languageButton == null) {
            return;
        }
        languageButton.setText(language == UiLanguage.FRENCH ? "ENGLISH" : "FRANÇAIS");
        networkButton.setText(language.text("RÉSEAU", "NETWORK"));
        processesButton.setText(language.text("PROCESSUS", "PROCESSES"));
        refreshButton.setText(language.text("↻  ACTUALISER", "↻  REFRESH"));
        cancelButton.setText(language.text("■  ARRÊTER", "■  STOP"));
        updateSearchPrompt();
        for (ToggleButton button : familyButtons) {
            SatelliteKind kind = (SatelliteKind) button.getUserData();
            button.setText(switch (kind) {
                case ADAPTER -> language.text("CARTES RÉSEAU", "NETWORK ADAPTERS");
                case PERIPHERAL -> language.text("PÉRIPHÉRIQUES", "PERIPHERALS");
                case PORT -> "PORTS";
                case SERVICE -> "SERVICES";
            });
        }
        principalViewButton.setText(language.text("PRINCIPAUX", "MAIN"));
        wholeViewButton.setText(language.text("VUE D’ENSEMBLE", "OVERVIEW"));
        pauseViewButton.setText(spacetime.paused()
                ? language.text("▷  REPRENDRE", "▷  RESUME")
                : "Ⅱ  PAUSE");
    }

    private void updateSearchPrompt() {
        if (search == null) return;
        search.setPromptText(view == View.PROCESSES
                ? language.text("RECHERCHER UN PROCESSUS", "SEARCH FOR A PROCESS")
                : language.text("RECHERCHER  ( port, processus, matériel… )",
                        "SEARCH  ( port, process, hardware… )"));
    }

    private Loading localized(Loading current) {
        if (current == null) return null;
        return new Loading(language.scanText(current.title()), language.scanText(current.detail()), current.progress());
    }

    private void installControls() {
        canvas.setOnScroll(event -> {
            double factor = event.getDeltaY() > 0 ? 1.18 : 1 / 1.18;
            if (view == View.PROCESSES) {
                spacetime.zoomAt(processCamera, event.getX(), event.getY(), factor, canvas.getWidth(), canvas.getHeight());
            } else {
                camera.zoomAt(event.getX(), event.getY(), factor);
            }
            markCameraMoved();
        });
        canvas.setOnMousePressed(event -> {
            dragX = event.getX();
            dragY = event.getY();
            canvasDragged = false;
        });
        canvas.setOnMouseDragged(event -> {
            if (view == View.PROCESSES) {
                if (event.isSecondaryButtonDown()) spacetime.tilt(event.getY() - dragY);
                else spacetime.pan(processCamera, event.getX() - dragX, event.getY() - dragY);
            } else {
                camera.pan(event.getX() - dragX, event.getY() - dragY);
            }
            canvasDragged = true;
            dragX = event.getX();
            dragY = event.getY();
            mouseX = event.getX();
            mouseY = event.getY();
            markCameraMoved();
        });
        canvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
            mouseInside = true;
        });
        canvas.setOnMouseExited(event -> mouseInside = false);

        // Double-clic : retour au cadrage d'ensemble, machine locale au centre
        canvas.setOnMouseClicked(event -> {
            if (canvasDragged || event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (view == View.PROCESSES) {
                String hit = spacetime.hitTest(event.getX(), event.getY());
                if (event.getClickCount() == 2) {
                    if (spacetime.focus(processCamera, processField, hit, canvas.getWidth(), canvas.getHeight())) {
                        selectedId = hit;
                        processCameraMoved = true;
                    } else {
                        selectedId = null;
                        processCameraMoved = false;
                        frameProcesses();
                    }
                } else {
                    selectedId = hit;
                }
                return;
            }
            if (event.getClickCount() == 2) {
                selectedId = null;
                cameraMoved = false;
                frameWholeSystem();
            } else if (constellation != null) {
                selectedId = renderer.hitTest(constellation, camera, event.getX(), event.getY());
            }
        });

        canvas.widthProperty().addListener((observable, before, after) -> reframeUntouchedViews());
        canvas.heightProperty().addListener((observable, before, after) -> reframeUntouchedViews());
    }

    /** Un redimensionnement recadre chaque vue que l'utilisateur n'a pas déplacée. */
    private void reframeUntouchedViews() {
        frameIfUntouched();
        if (!processCameraMoved) {
            frameProcesses();
        }
    }

    private void refreshScan() {
        cancelScan(false);
        AtomicBoolean token = new AtomicBoolean();
        scanCancelled = token;
        selectedId = null;
        statusText = "Analyse de la machine locale…";
        loading = Loading.indeterminate(
                "Analyse du système", "Inventaire de la machine locale");
        refreshButton.setDisable(true);
        cancelButton.setDisable(false);
        scanThread = Thread.ofVirtual().name("scan-localhost").start(() -> {
            try {
                Device scanned = SystemScanner.scanLocalHost();
                if (token.get()) return;
                Platform.runLater(() -> {
                    localHost = scanned;
                    discovered.clear();
                    bluetooth.clear();
                    rebuild();
                    scanNetwork(token);
                });
                // Le Bluetooth ne dépend pas du réseau : il est lu pendant le balayage au
                // lieu de retarder de plusieurs secondes l'apparition de la carte.
                List<Device> paired = SystemScanner.scanBluetooth();
                if (!token.get()) {
                    Platform.runLater(() -> {
                        if (!token.get()) {
                            bluetooth.clear();
                            bluetooth.addAll(paired);
                            rebuild();
                        }
                    });
                }
            } catch (RuntimeException e) {
                Platform.runLater(() -> finishScan(token, "Analyse locale impossible : " + e.getMessage()));
            }
        });
    }

    /** Balaye le réseau local et fait apparaître chaque appareil dès qu'il est trouvé. */
    private void scanNetwork(AtomicBoolean token) {
        loading = Loading.indeterminate(
                "Cartographie du réseau", "Détection du sous-réseau et de la route par défaut");
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
                Platform.runLater(() -> {
                    statusText = "Balayage " + subnet.localAddress() + "/"
                            + subnet.prefixLength() + " : 0/" + total;
                    loading = Loading.progress("Balayage du réseau",
                            "0 / " + total + " adresses examinées", 0);
                });

                NetworkScanner.scan(subnet,
                        device -> Platform.runLater(() -> publishDevice(token, device)),
                        done -> {
                            if (done % progressStep == 0 || done == total) {
                                Platform.runLater(() -> {
                                    if (!token.get()) {
                                        statusText = "Balayage réseau : " + done + "/" + total
                                                + "     " + discovered.size() + " appareil(s)";
                                        loading = Loading.progress("Balayage du réseau",
                                                done + " / " + total + " adresses examinées",
                                                total == 0 ? 1 : done / (double) total);
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
                        loading = Loading.indeterminate("Identification",
                                discovered.size() + " appareil(s) à nommer");
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
        loading = null;
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
        long pairedActive = bluetooth.stream()
                .filter(device -> device.getPresence() == Presence.ACTIVE).count();
        return machines().size() + " appareil(s) réseau     "
                + pairedActive + "/" + bluetooth.size() + " bluetooth actif(s)     "
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
        // Les appareils Bluetooth sont appairés à cette machine : ils gravitent autour
        // d'elle, comme les clients du réseau gravitent autour de la box.
        for (Device paired : bluetooth) {
            rebuilt.addDevice(paired);
            rebuilt.addLink(new NetworkLink(localHost, paired, "Bluetooth"));
        }
        constellation = ConstellationLayout.compute(rebuilt);
        // Le battement lit ces deux valeurs depuis son propre thread.
        liveGateway.set(hub.equals(localHost) ? null : hub.getIpAddress());
        liveLocalAddress.set(localHost.getIpAddress());
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

    /**
     * JavaFX appelle toujours cette méthode à l'extinction, quel que soit le chemin.
     *
     * setOnCloseRequest, lui, ne se déclenche que sur une demande du gestionnaire de
     * fenêtres. Depuis que la fenêtre est sans décoration et que notre bouton appelle
     * stage.close(), il ne passait plus : le battement et le balayage n'étaient donc
     * plus arrêtés explicitement. Sans conséquence visible — les threads virtuels sont
     * démons et la JVM s'éteint — mais c'est un nettoyage qui ne s'exécutait pas.
     */
    @Override
    public void stop() {
        shutdown();
    }

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "MbaliScope");
        launch(args);
    }
}
