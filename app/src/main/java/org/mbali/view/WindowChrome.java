package org.mbali.view;

import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * La barre de fenêtre, dessinée dans le vocabulaire de la carte.
 *
 * La fenêtre est sans décoration système : c'est la condition pour avoir ses propres
 * commandes. En contrepartie, le déplacement et l'agrandissement du bureau sont perdus
 * et refaits ici — l'accrochage aux bords de Windows, lui, ne revient pas.
 *
 * Les trois symboles restent des formes universelles (trait, cadre, croix) : les
 * habiller en glyphes de la carte les rendrait jolis et illisibles, or ce sont les
 * seules commandes dont l'utilisateur a besoin sans hésiter.
 */
public final class WindowChrome {

    private static final Color BRIGHT = Color.web("#f2f2f5");
    private static final Color DIM = Color.web("#8c8c98");
    private static final Color DANGER = Color.web("#ff2d55");

    public static final double BAR_HEIGHT = 34;
    private static final double BUTTON = 34;

    private final Stage stage;
    private Rectangle2D windowed;
    private boolean filled = true;

    private double grabX;
    private double grabY;

    public WindowChrome(Stage stage) {
        this.stage = stage;
    }

    /** Pose la fenêtre exactement sur l'écran utile, barre des tâches exclue. */
    public void fillScreen() {
        Rectangle2D visible = Screen.getPrimary().getVisualBounds();
        stage.setX(visible.getMinX());
        stage.setY(visible.getMinY());
        stage.setWidth(visible.getWidth());
        stage.setHeight(visible.getHeight());
        filled = true;
    }

    /** La zone sensible : glisser déplace, double-clic bascule. */
    public Pane titleBar() {
        Pane bar = new Pane();
        bar.setPrefHeight(BAR_HEIGHT);
        bar.setMinHeight(BAR_HEIGHT);
        bar.prefWidthProperty().bind(stage.widthProperty());
        bar.setStyle("-fx-background-color: transparent;");

        ImageView brand = new ImageView(AppIcon.render(22));
        brand.setFitWidth(22);
        brand.setFitHeight(22);
        brand.setPreserveRatio(true);
        brand.setSmooth(true);
        brand.setLayoutX(9);
        brand.setLayoutY((BAR_HEIGHT - 22) / 2);
        brand.setMouseTransparent(true);
        bar.getChildren().add(brand);

        bar.setOnMousePressed(event -> {
            grabX = event.getScreenX() - stage.getX();
            grabY = event.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(event -> {
            if (filled) {
                return; // une fenêtre plein écran ne se traîne pas
            }
            stage.setX(event.getScreenX() - grabX);
            stage.setY(event.getScreenY() - grabY);
        });
        bar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                toggle();
            }
        });
        return bar;
    }

    /** Les trois commandes, alignées à droite. */
    public HBox buttons() {
        HBox row = new HBox(2,
                button(Glyph.MINIMISE, () -> stage.setIconified(true)),
                button(Glyph.TOGGLE, this::toggle),
                button(Glyph.CLOSE, stage::close));
        row.setPickOnBounds(false);
        return row;
    }

    private void toggle() {
        if (filled) {
            Rectangle2D back = windowed == null
                    ? new Rectangle2D(stage.getX() + 120, stage.getY() + 90,
                            Math.max(stage.getWidth() * .66, 900),
                            Math.max(stage.getHeight() * .66, 620))
                    : windowed;
            stage.setX(back.getMinX());
            stage.setY(back.getMinY());
            stage.setWidth(back.getWidth());
            stage.setHeight(back.getHeight());
            filled = false;
        } else {
            windowed = new Rectangle2D(stage.getX(), stage.getY(),
                    stage.getWidth(), stage.getHeight());
            fillScreen();
        }
    }

    private enum Glyph { MINIMISE, TOGGLE, CLOSE }

    private Canvas button(Glyph glyph, Runnable action) {
        Canvas canvas = new Canvas(BUTTON, BUTTON);
        paint(canvas, glyph, false);
        canvas.setOnMouseEntered(event -> paint(canvas, glyph, true));
        canvas.setOnMouseExited(event -> paint(canvas, glyph, false));
        canvas.setOnMouseClicked(event -> action.run());
        return canvas;
    }

    private void paint(Canvas canvas, Glyph glyph, boolean hover) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, BUTTON, BUTTON);

        Color ink = hover ? (glyph == Glyph.CLOSE ? DANGER : BRIGHT) : DIM;
        if (hover) {
            // Un cerclage discret plutôt qu'un aplat : la carte n'a aucun aplat.
            gc.setStroke(ink.deriveColor(0, 1, 1, .35));
            gc.setLineWidth(1);
            gc.strokeOval(4.5, 4.5, BUTTON - 9, BUTTON - 9);
        }
        gc.setStroke(ink);
        gc.setLineWidth(1.2);

        double c = BUTTON / 2.0;
        switch (glyph) {
            case MINIMISE -> {
                gc.strokeLine(c - 5, c, c + 5, c);
                // Deux graduations aux extrémités, comme la couronne de la carte
                gc.strokeLine(c - 5, c - 2.5, c - 5, c + 2.5);
                gc.strokeLine(c + 5, c - 2.5, c + 5, c + 2.5);
            }
            case TOGGLE -> {
                if (filled) {
                    gc.strokeRect(c - 5.5, c - 3.5, 9, 7);
                    gc.strokeLine(c - 3, c - 5.5, c + 5.5, c - 5.5);
                    gc.strokeLine(c + 5.5, c - 5.5, c + 5.5, c + 2);
                } else {
                    gc.strokeRect(c - 5, c - 5, 10, 10);
                }
            }
            case CLOSE -> {
                gc.strokeLine(c - 5, c - 5, c + 5, c + 5);
                gc.strokeLine(c + 5, c - 5, c - 5, c + 5);
            }
        }
    }
}
