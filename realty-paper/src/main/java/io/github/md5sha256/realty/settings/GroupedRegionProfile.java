package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import io.github.md5sha256.realty.api.RegionState;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

@ConfigurationObject
public record GroupedRegionProfile(@ConfigurationValue(path = "regions") @NotNull Set<String> regions,
                                   @ConfigurationValue(path = "states") @NotNull Map<RegionState, RegionProfile> states) {
}
