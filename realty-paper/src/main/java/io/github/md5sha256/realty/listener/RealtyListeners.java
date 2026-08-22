package io.github.md5sha256.realty.listener;

import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The listener tier's registry: every listener the framework registers on enable.
 *
 * <p>{@link PropertyTaxListener} is deliberately absent. It needs Treasury's API, which may not be
 * installed, and {@code ListenerManager} registers everything it is given unconditionally — so it
 * stays hand-registered behind the same Treasury check as before.</p>
 */
public final class RealtyListeners {

    private static final @NotNull List<Class<? extends Listener>> LISTENERS = List.of(
            SignInteractionListener.class,
            SubregionWandListener.class,
            RegionNotificationListener.class);

    /** {@link #LISTENERS} as the array the framework's builder takes. */
    @SuppressWarnings("unchecked")
    public static @NotNull Class<? extends Listener>[] listeners() {
        return LISTENERS.toArray(new Class[0]);
    }

    private RealtyListeners() {
    }
}
