package io.github.md5sha256.realty.api;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The sign template attached to a region profile: the lines rendered on the sign, and the
 * commands run as the clicking player.
 */
@ConfigurationObject
public record SignProfile(
        @ConfigurationValue(path = "lines") @NotNull List<String> lines,
        @ConfigurationValue(path = "right-click-commands") @Nullable List<String> rightClickCommands,
        @ConfigurationValue(path = "left-click-commands") @Nullable List<String> leftClickCommands
) {

    public SignProfile {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
