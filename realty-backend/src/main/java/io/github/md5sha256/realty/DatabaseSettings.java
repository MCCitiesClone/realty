package io.github.md5sha256.realty;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import org.jetbrains.annotations.NotNull;

/**
 * JDBC connection details, read from {@code database.yml}.
 *
 * <p>Deliberately has no defaults: an unset {@code url} is how the plugin detects that an
 * operator has not configured a database yet, and it refuses to enable rather than silently
 * pointing somewhere unintended.</p>
 */
@ConfigurationComponent(file = "database.yml")
public record DatabaseSettings(
        @ConfigurationValue(path = "url") @NotNull String url,
        @ConfigurationValue(path = "username") @NotNull String username,
        @ConfigurationValue(path = "password") @NotNull String password
) {

    public DatabaseSettings {
        url = url == null ? "" : url;
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }
}
