package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.github.md5sha256.realty.api.RegionState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

@ConfigurationComponent(file = "profiles.yml")
public record RegionProfileSettings(
        @ConfigurationValue(path = "global") @Nullable Map<RegionState, RegionProfile> global,
        @ConfigurationValue(path = "grouped") @Nullable List<GroupedRegionProfile> grouped
) {
}
