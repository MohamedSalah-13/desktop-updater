/**
 * Update check, download and install for desktop programs.
 *
 * <p>{@code com.hamza.updater} needs nothing but {@code java.base}; {@code com.hamza.updater.fx} is the JavaFX
 * window over it.
 */
module com.hamza.updater {
    requires transitive javafx.controls;

    exports com.hamza.updater;
    exports com.hamza.updater.fx;
}
