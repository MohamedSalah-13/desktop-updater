package com.hamza.updater.fx;

import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.function.Consumer;

/**
 * Draws a release's notes as {@link Notes} read them: headings as headings, lists as lists, links that
 * open.
 *
 * <p>The words take their colour from a label in the same pane, so that they are readable in whatever
 * theme the program wears - a bare {@code Text} would be black on a dark one.
 */
final class NotesView {

    private NotesView() {
    }

    /** @param open what a pressed link does with its address */
    static ScrollPane of(String markdown, Consumer<String> open) {
        Label ink = new Label();
        ink.setManaged(false);
        ink.setVisible(false);

        VBox column = new VBox(6);
        column.setPadding(new Insets(8, 10, 8, 10));
        column.getChildren().add(ink);
        // Notes read in their own direction, whatever the window's language is.
        column.setNodeOrientation(Notes.rightToLeft(markdown) ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        for (Notes.Block block : Notes.read(markdown)) {
            column.getChildren().add(node(block, ink, open));
        }

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(430);
        scroll.setPrefViewportHeight(200);
        scroll.setMaxHeight(300);
        return scroll;
    }

    private static Node node(Notes.Block block, Label ink, Consumer<String> open) {
        if (block.kind() == Notes.Kind.RULE) {
            return new Separator();
        }
        TextFlow flow = new TextFlow();
        String size = switch (block.kind()) {
            case HEADING -> block.level() == 1 ? "1.3em" : block.level() == 2 ? "1.15em" : "1.05em";
            default -> "1em";
        };
        boolean heading = block.kind() == Notes.Kind.HEADING;
        if (!block.marker().isEmpty()) {
            flow.getChildren().add(text(block.marker() + "  ", ink, size, false, false, false));
        }
        for (Notes.Span span : block.spans()) {
            boolean code = span.code() || block.kind() == Notes.Kind.CODE;
            boolean italic = span.italic() || block.kind() == Notes.Kind.QUOTE;
            if (span.link() != null) {
                Hyperlink link = new Hyperlink(span.text());
                link.setPadding(Insets.EMPTY);
                link.setStyle("-fx-font-size: " + size + ";" + (span.bold() || heading ? " -fx-font-weight: bold;" : ""));
                link.setOnAction(pressed -> open.accept(span.link()));
                flow.getChildren().add(link);
            } else {
                // Code - a path, a command - is read left to right even inside an Arabic sentence; without
                // the embedding marks its dots and slashes would be reordered around it.
                String shown = code ? "‪" + span.text() + "‬" : span.text();
                flow.getChildren().add(text(shown, ink, size, span.bold() || heading, italic, code));
            }
        }
        if (block.kind() == Notes.Kind.CODE) {
            flow.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        }
        double indent = switch (block.kind()) {
            case BULLET, NUMBERED -> 4 + 18.0 * block.level();
            case QUOTE, CODE -> 12;
            default -> 0;
        };
        // Insets follow the reading direction: "left" here is the side a line starts on.
        flow.setPadding(new Insets(heading ? 4 : 0, 0, 0, indent));
        return flow;
    }

    private static Text text(String content, Label ink, String size, boolean bold, boolean italic, boolean code) {
        Text text = new Text(content);
        text.fillProperty().bind(ink.textFillProperty());
        text.setStyle("-fx-font-size: " + size + ";"
                + (bold ? " -fx-font-weight: bold;" : "")
                + (italic ? " -fx-font-style: italic;" : "")
                + (code ? " -fx-font-family: 'Consolas', 'Cascadia Mono', monospace;" : ""));
        return text;
    }
}
