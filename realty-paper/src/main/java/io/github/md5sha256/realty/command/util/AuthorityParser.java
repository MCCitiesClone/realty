package io.github.md5sha256.realty.command.util;

import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.github.md5sha256.realty.party.PartyService;
import org.incendo.cloud.paper.util.sender.Source;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Parses a party argument into the UUID stored in a region's authority, title-holder, landlord or
 * tenant column.
 *
 * <p>Two flavours. {@link #authority()} accepts players only, for arguments that name an individual
 * (an agent invite, for instance). {@link #party(PartyService)} additionally accepts
 * {@code gov:<Name>}, resolving a registered Treasury government to the synthetic UUID that stands
 * for it, for the four slots a government may hold.
 */
public class AuthorityParser implements ArgumentParser<Source, UUID> {

    /**
     * Prefix marking a government rather than a player, mirroring the {@code g:} group prefix that
     * {@code /realty add} uses. A player name can never contain {@code :}, so the two namespaces
     * cannot collide.
     */
    public static final String GOVERNMENT_PREFIX = "gov:";

    private final @Nullable PartyService partyService;

    private AuthorityParser(@Nullable PartyService partyService) {
        this.partyService = partyService;
    }

    /**
     * Accepts player names only.
     */
    public static @NotNull ParserDescriptor<Source, UUID> authority() {
        return ParserDescriptor.of(new AuthorityParser(null), UUID.class);
    }

    /**
     * Accepts a player name, or {@code gov:<Name>} naming a registered government.
     *
     * <p>Resolution is from the in-memory party cache, so parsing costs no I/O: a government has to
     * be registered with {@code /realty government register} before it can be named here.
     */
    public static @NotNull ParserDescriptor<Source, UUID> party(@NotNull PartyService partyService) {
        return ParserDescriptor.of(new AuthorityParser(partyService), UUID.class);
    }

    @Override
    public @NotNull ArgumentParseResult<UUID> parse(
            @NotNull CommandContext<Source> ctx,
            @NotNull CommandInput input
    ) {
        String name = input.readString();
        if (partyService != null && name.regionMatches(true, 0, GOVERNMENT_PREFIX, 0,
                GOVERNMENT_PREFIX.length())) {
            String governmentName = name.substring(GOVERNMENT_PREFIX.length());
            return partyService.partyByName(governmentName)
                    .map(party -> ArgumentParseResult.success(party.partyUuid()))
                    .orElseGet(() -> ArgumentParseResult.failure(new IllegalArgumentException(
                            "No registered government named '" + governmentName
                                    + "'. Register it first with /realty government register <name>.")));
        }
        Player onlinePlayer = Bukkit.getPlayerExact(name);
        if (onlinePlayer != null) {
            return ArgumentParseResult.success(onlinePlayer.getUniqueId());
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayerIfCached(name);
        if (offlinePlayer == null || !offlinePlayer.hasPlayedBefore()) {
            return ArgumentParseResult.failure(
                    new IllegalArgumentException("Player not found: " + name));
        }
        return ArgumentParseResult.success(offlinePlayer.getUniqueId());
    }

    @Override
    public @NotNull SuggestionProvider<Source> suggestionProvider() {
        return (ctx, input) -> {
            List<Suggestion> suggestions = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(Suggestion.suggestion(player.getName()));
            }
            if (partyService != null) {
                for (GovernmentPartyEntity party : partyService.parties()) {
                    suggestions.add(Suggestion.suggestion(GOVERNMENT_PREFIX + PartyService.commandName(party)));
                }
            }
            return CompletableFuture.completedFuture(suggestions);
        };
    }
}
