package io.github.md5sha256.realty.command.resolver;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.md5sha256.realty.command.util.NamedAuthority;
import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.github.md5sha256.realty.party.PartyService;
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
 * Resolves a party — a player or a registered government — for the arguments that accept either,
 * such as a title holder, landlord or authority.
 *
 * <p>Players win a tie: a token is read as a player first, and only falls through to a government
 * of the same name if nobody by that name has played here. A government sharing its name with a
 * player is reached explicitly with {@code gov.<Name>}.</p>
 */
@Singleton
public final class NamedAuthorityResolver implements ParameterResolver<NamedAuthority> {

    private final PartyService parties;

    @Inject
    public NamedAuthorityResolver(@NotNull PartyService parties) {
        this.parties = parties;
    }

    @Override
    public @NotNull Class<NamedAuthority> type() {
        return NamedAuthority.class;
    }

    @Override
    public @NotNull Optional<NamedAuthority> resolve(@NotNull String token,
                                                     @NotNull CommandSender sender) {
        String forced = AuthorityNames.governmentNameIfPrefixed(token);
        if (forced != null) {
            return this.parties.partyByName(forced)
                    .map(party -> new NamedAuthority(party.partyUuid(), party.displayName()));
        }

        Player online = Bukkit.getPlayerExact(token);
        if (online != null) {
            return Optional.of(new NamedAuthority(online.getUniqueId(), online.getName()));
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(token);
        if (offline != null && offline.hasPlayedBefore() && offline.getName() != null) {
            return Optional.of(new NamedAuthority(offline.getUniqueId(), offline.getName()));
        }

        // A bare government name works too, so the common case needs no prefix at all.
        return this.parties.partyByName(token)
                .map(party -> new NamedAuthority(party.partyUuid(), party.displayName()));
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
        // Suggested bare: that is what a sender normally types, and what the client will accept.
        // The gov. form stays available for disambiguation.
        for (GovernmentPartyEntity party : this.parties.parties()) {
            String name = PartyService.commandName(party);
            if (name.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }
}
