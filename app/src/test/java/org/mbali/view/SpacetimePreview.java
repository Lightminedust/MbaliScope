package org.mbali.view;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.mbali.model.ProcessSnapshot;
import org.mbali.service.SystemScanner;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;

/** Capture reproductible du vrai Canvas JavaFX, sans fenêtre ni balayage réseau. */
public final class SpacetimePreview {
    public static void main(String[] args) throws Exception {
        boolean live = args.length > 0 && args[0].equals("live");
        List<ProcessSnapshot> first = live ? SystemScanner.scanProcesses() : fixture(false);
        Instant firstAt = Instant.now();
        if (live && first.isEmpty()) throw new IllegalStateException("Lecture système indisponible");
        OptionalLong memory = live ? SystemScanner.totalMemoryBytes() : OptionalLong.of(24L << 30);
        int cores = live ? Runtime.getRuntime().availableProcessors() : 12;
        var previous = SpacetimeLayout.compute(first, memory, cores, firstAt, OptionalLong.of(ProcessHandle.current().pid()));
        if (live) Thread.sleep(3_000);
        List<ProcessSnapshot> next = live ? SystemScanner.scanProcesses() : fixture(true);
        if (next.isEmpty()) throw new IllegalStateException("Deuxième lecture système indisponible");
        var field = SpacetimeLayout.compute(next, memory, cores, live ? Instant.now() : firstAt.plusSeconds(3),
                OptionalLong.of(ProcessHandle.current().pid()), previous);
        Path directory = Path.of("build", "previews");
        Files.createDirectories(directory);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                double width = 1440, height = 1000;
                Canvas canvas = new Canvas(width, height);
                new Scene(new Pane(canvas));
                SpacetimeRenderer renderer = new SpacetimeRenderer();
                Camera camera = new Camera();
                for (int i = 0; i < 20; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), null, camera, width, height, i / 60.0, 1.0 / 60,
                            0, 0, false, null, "", "LECTURE DES PROCESSUS");
                }
                capture(canvas, directory.resolve("processes-loading.png"));
                renderer.frame(camera, field, width, height, false);
                camera.settle();
                for (int i = 0; i < 90; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), field, camera, width, height, i / 10.0, .1,
                            0, 0, false, null, "", "PROCESSUS · " + (live ? "RELEVÉ LOCAL" : "SCÉNARIO DE VÉRIFICATION"));
                    if (i % 15 == 0) canvas.snapshot(null, null); // vider la file des commandes Canvas
                }
                long start = System.nanoTime();
                for (int i = 0; i < 120; i++) {
                    renderer.draw(canvas.getGraphicsContext2D(), field, camera, width, height, 9 + i / 60.0, 1.0 / 60,
                            0, 0, false, null, "", "PROCESSUS · " + (live ? "RELEVÉ LOCAL" : "SCÉNARIO DE VÉRIFICATION"));
                    if (i % 10 == 0) canvas.snapshot(null, null);
                }
                canvas.snapshot(null, null);
                double millisPerFrame = (System.nanoTime() - start) / 120_000_000.0;
                capture(canvas, directory.resolve(live ? "processes-live.png" : "processes-principaux.png"));
                System.out.printf("%d processus / %d familles, %.1f ms par image (Canvas + captures périodiques)%n",
                        field.processCount(), field.masses().size(), millisPerFrame);
                renderer.frame(camera, field, width, height, true);
                camera.settle();
                renderer.draw(canvas.getGraphicsContext2D(), field, camera, width, height, 9, 0, 0, 0, false, null, "", "VUE D’ENSEMBLE");
                capture(canvas, directory.resolve(live ? "processes-live-overview.png" : "processes-overview.png"));
                renderer.focus(camera, field, "chrome", width, height);
                camera.settle();
                renderer.draw(canvas.getGraphicsContext2D(), field, camera, width, height, 9, 0, 0, 0, false, null, "", "EXPLORATION D’UNE FAMILLE");
                capture(canvas, directory.resolve(live ? "processes-live-detail.png" : "processes-detail.png"));
                renderer.setPaused(true);
                renderer.draw(canvas.getGraphicsContext2D(), field, camera, width, height, 9, 0,
                        0, 0, false, null, "", "PAUSE");
                WritableImage frozen = canvas.snapshot(null, null);
                renderer.draw(canvas.getGraphicsContext2D(), previous, camera, width, height, 90, 30,
                        0, 0, false, null, "", "PAUSE");
                WritableImage later = canvas.snapshot(null, null);
                for (int y = 0; y < (int) height; y += 2) for (int x = 0; x < (int) width; x += 2) {
                    if (frozen.getPixelReader().getArgb(x, y) != later.getPixelReader().getArgb(x, y))
                        throw new AssertionError("La vue a bougé pendant la pause");
                }
                System.out.println("Pause vérifiée : image identique malgré un nouveau relevé et 30 secondes écoulées.");
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        boolean completed = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!completed) throw new IllegalStateException("Capture expirée");
        if (failure.get() != null) throw new IllegalStateException("Capture impossible", failure.get());
        System.out.println("Captures : " + directory.toAbsolutePath());
    }

    private static void capture(Canvas canvas, Path target) throws Exception {
        WritableImage image = canvas.snapshot(null, null);
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) {
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        ImageIO.write(png, "png", target.toFile());
    }

    private static List<ProcessSnapshot> fixture(boolean next) {
        Random random = new Random(1029);
        List<ProcessSnapshot> processes = new ArrayList<>();
        String[] names = {"Code.exe", "chrome.exe", "svchost.exe", "java.exe", "Memory Compression", "idea64.exe", "msedgewebview2.exe"};
        int[] counts = {20, 17, 103, 6, 1, 3, 12};
        long[] megabytes = {3_500, 2_200, 1_800, 1_700, 1_500, 1_200, 600};
        long pid = 100;
        Instant boot = Instant.parse("2026-09-13T06:00:00Z");
        for (int family = 0; family < 117; family++) {
            int count = family < counts.length ? counts[family] : 1;
            String name = family < names.length ? names[family] : "service-" + family + ".exe";
            long total = family < megabytes.length ? megabytes[family] : 2 + random.nextInt(90);
            long principal = pid;
            for (int i = 0; i < count; i++) {
                long ram = total * (1L << 20) / count;
                long extraCpu = next ? (long) ((family < 7 ? .30 : .004) * 3_000_000_000L / count) : 0;
                processes.add(new ProcessSnapshot(pid++, OptionalLong.of(i == 0 ? 4 : principal), name,
                        Optional.of(boot.plusSeconds(pid)), Optional.of(Duration.ofSeconds(400).plusNanos(extraCpu)),
                        OptionalLong.of(ram), Optional.empty()));
            }
        }
        return processes;
    }
}
