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
     * Prefix that forces a government rather than a player.
     *
     * <p>The separator is a dot, not a colon: Brigadier only accepts
     * {@code 0-9 A-Z a-z _ - . +} in an unquoted argument, and Cloud maps this parser to
     * {@code StringArgumentType.word()}, so a colon ends the token and the client rejects the
     * command before it is ever sent. A Minecraft username cannot contain a dot either, so
     * {@code gov.<Name>} still cannot collide with a player.
     */
    public static final String GOVERNMENT_PREFIX = "gov.";

    /**
     * Accepted but never suggested. Console and script callers are parsed server-side, where a
     * colon is harmless, so anything already written against the original syntax keeps working.
     */
    public static final String LEGACY_GOVERNMENT_PREFIX = "gov:";

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
     * Accepts a player name, a registered government's name, or {@code gov.<Name>} to force the
     * latter when a player happens to share the name.
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
        String forced = governmentNameIfPrefixed(name);
        if (partyService != null && forced != null) {
            return partyService.partyByName(forced)
                    .map(party -> ArgumentParseResult.success(party.partyUuid()))
                    .orElseGet(() -> ArgumentParseResult.<UUID>failure(new IllegalArgumentException(
                            "No registered government named '" + forced
                                    + "'. Register it first with /realty government register <name>.")));
        }
        Player onlinePlayer = Bukkit.getPlayerExact(name);
        if (onlinePlayer != null) {
            return ArgumentParseResult.success(onlinePlayer.getUniqueId());
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayerIfCached(name);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
            return ArgumentParseResult.success(offlinePlayer.getUniqueId());
        }
        // A bare government name works too, so the common case needs no prefix at all. Players win
        // a tie: a government sharing a name with someone who has played here needs gov.<Name>.
        if (partyService != null) {
            var party = partyService.partyByName(name);
            if (party.isPresent()) {
                return ArgumentParseResult.success(party.get().partyUuid());
            }
        }
        return ArgumentParseResult.failure(
                new IllegalArgumentException("No player or registered government named: " + name));
    }

    /**
     * Returns the government name a token forces, or {@code null} if it names no government
     * explicitly. Both the current dot form and the original colon form are accepted.
     */
    static @Nullable String governmentNameIfPrefixed(@NotNull String token) {
        for (String prefix : new String[]{GOVERNMENT_PREFIX, LEGACY_GOVERNMENT_PREFIX}) {
            if (token.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }

    @Override
    public @NotNull SuggestionProvider<Source> suggestionProvider() {
        return (ctx, input) -> {
            List<Suggestion> suggestions = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(Suggestion.suggestion(player.getName()));
            }
            if (partyService != null) {
                // Suggested bare: that is what a sender normally types, and it is what the
                // client will accept. The gov. form stays available for disambiguation.
                for (GovernmentPartyEntity party : partyService.parties()) {
                    suggestions.add(Suggestion.suggestion(PartyService.commandName(party)));
                }
            }
            return CompletableFuture.completedFuture(suggestions);
        };
    }
}
