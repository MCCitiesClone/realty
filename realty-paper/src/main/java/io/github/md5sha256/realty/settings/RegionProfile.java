package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import io.github.md5sha256.realty.api.SignProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

@ConfigurationObject
public record RegionProfile(
        @ConfigurationValue(path = "priority") @Nullable Integer priority,
        @ConfigurationValue(path = "flags") @NotNull Map<String, String> flags,
        @ConfigurationValue(path = "sign") @Nullable SignProfile sign) {
}
