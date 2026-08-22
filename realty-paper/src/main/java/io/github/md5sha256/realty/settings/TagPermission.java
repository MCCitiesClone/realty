package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ConfigurationObject
public record TagPermission(
        @ConfigurationValue(path = "node") @NotNull String node,
        @ConfigurationValue(path = "default") @NotNull PermissionDefault permissionDefault
) {

    public enum PermissionDefault {
        OP,
        TRUE,
        FALSE
    }

}
