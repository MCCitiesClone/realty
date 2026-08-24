package io.github.md5sha256.realty.command.resolver;

import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a player by name, for arguments that accept only players — agent invites and removals,
 * where a government is not a valid subject.
 *
 * <p>Resolves from the local player cache: an online player first, then one who has played here
 * before. Never blocks on a name lookup against Mojang, because this runs on the command path.</p>
 */
public final class PlayerAuthorityResolver implements ParameterResolver<PlayerAuthority> {

    @Override
    public @NotNull Class<PlayerAuthority> type() {
        return PlayerAuthority.class;
    }

    @Override
    public @NotNull Optional<PlayerAuthority> resolve(@NotNull String token,
                                                      @NotNull CommandSender sender) {
        Player online = Bukkit.getPlayerExact(token);
        if (online != null) {
            return Optional.of(new PlayerAuthority(online.getUniqueId(), online.getName()));
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(token);
        if (offline != null && offline.hasPlayedBefore() && offline.getName() != null) {
            return Optional.of(new PlayerAuthority(offline.getUniqueId(), offline.getName()));
        }
        return Optional.empty();
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
