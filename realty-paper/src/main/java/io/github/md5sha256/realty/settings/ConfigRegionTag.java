package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ConfigurationObject
public record ConfigRegionTag(
        @ConfigurationValue(path = "tag-id") @NotNull String tagId,
        @ConfigurationValue(path = "tag-display-name") @NotNull Component tagDisplayName,
        @ConfigurationValue(path = "permission") @Nullable TagPermission permission
) {
}
