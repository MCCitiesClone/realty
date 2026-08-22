package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

@ConfigurationComponent(file = "region-tags.yml")
public record RegionTagSettings(
        @ConfigurationValue(path = "tags") @NotNull List<ConfigRegionTag> tags
) {
    public RegionTagSettings(@Nullable List<ConfigRegionTag> tags) {
        this.tags = Objects.requireNonNullElse(tags, List.of());
    }
}
