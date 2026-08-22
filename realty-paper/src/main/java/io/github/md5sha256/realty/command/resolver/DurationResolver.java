package io.github.md5sha256.realty.command.resolver;

import com.minecraftcitiesnetwork.pluginInfrastructure.util.DurationParserUtil;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a duration written the way an operator thinks of one — {@code 7d}, {@code 12h},
 * {@code 30m} — rather than as an ISO-8601 period.
 */
public final class DurationResolver implements ParameterResolver<Duration> {

    @Override
    public @NotNull Class<Duration> type() {
        return Duration.class;
    }

    @Override
    public @NotNull Optional<Duration> resolve(@NotNull String token,
                                               @NotNull CommandSender sender) {
        try {
            return Optional.of(DurationParserUtil.parse(token));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull List<String> suggestions(@NotNull String prefix, @NotNull CommandSender sender) {
        return List.of("1h", "12h", "1d", "7d", "30d");
    }
}
