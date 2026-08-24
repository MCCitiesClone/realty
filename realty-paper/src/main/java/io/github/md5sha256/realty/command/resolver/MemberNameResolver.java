package io.github.md5sha256.realty.command.resolver;

import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a {@link MemberName}, suggesting online players.
 *
 * <p>Accepts any non-blank token: the name may belong to a player who has never joined, or be a
 * WorldGuard group, and WorldGuard itself is the authority on whether it means anything.</p>
 */
public final class MemberNameResolver implements ParameterResolver<MemberName> {

    @Override
    public @NotNull Class<MemberName> type() {
        return MemberName.class;
    }

    @Override
    public @NotNull Optional<MemberName> resolve(@NotNull String token,
                                                 @NotNull CommandSender sender) {
        return token.isBlank() ? Optional.empty() : Optional.of(new MemberName(token));
    }

    @Override
    public @NotNull List<String> suggestions(@NotNull String prefix, @NotNull CommandSender sender) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lowered)) {
                names.add(player.getName());
            }
        }
        return List.copyOf(names);
    }
}
