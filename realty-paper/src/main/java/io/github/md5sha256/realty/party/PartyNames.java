package io.github.md5sha256.realty.party;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Renders a party UUID as the name to show players.
 *
 * <p>A government's UUID belongs to no player, so asking Bukkit for its name yields nothing and the
 * raw UUID ends up in chat. Every message that names an authority, title holder, landlord or tenant
 * goes through here instead.
 */
public final class PartyNames {

    private PartyNames() {
    }

    /**
     * Returns a government's display name, an online or known player's username, or the UUID itself
     * when nothing better is available.
     *
     * @param parties   resolves government parties
     * @param partyUuid the party to name
     * @return a human-readable name
     */
    public static @NotNull String resolve(@NotNull PartyService parties, @NotNull UUID partyUuid) {
        return parties.governmentName(partyUuid).orElseGet(() -> playerName(partyUuid));
    }

    /**
     * Player-name resolution without the government lookup, for arguments that can only ever be a
     * player (an agent, a bidder, an offerer).
     */
    public static @NotNull String playerName(@NotNull UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
        String name = offline.getName();
        return name != null ? name : playerId.toString();
    }
}
