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
import java.util.concurrent.CompletableFuture;

/**
 * Like {@link AuthorityParser}, but keeps the name the sender typed alongside the resolved UUID so
 * a command can echo it back without a second lookup.
 *
 * <p>{@link #party(PartyService)} additionally accepts a registered government's name, or
 * {@code gov.<Name>} to force one, in which case the captured name is the government's display
 * name.
 */
public class NamedAuthorityParser implements ArgumentParser<Source, NamedAuthority> {

    private final @Nullable PartyService partyService;

    private NamedAuthorityParser(@Nullable PartyService partyService) {
        this.partyService = partyService;
    }

    /**
     * Accepts player names only.
     */
    public static @NotNull ParserDescriptor<Source, NamedAuthority> namedAuthority() {
        return ParserDescriptor.of(new NamedAuthorityParser(null), NamedAuthority.class);
    }

    /**
     * Accepts a player name, a registered government's name, or {@code gov.<Name>} to force the
     * latter.
     */
    public static @NotNull ParserDescriptor<Source, NamedAuthority> party(@NotNull PartyService partyService) {
        return ParserDescriptor.of(new NamedAuthorityParser(partyService), NamedAuthority.class);
    }

    @Override
    public @NotNull ArgumentParseResult<NamedAuthority> parse(
            @NotNull CommandContext<Source> ctx,
            @NotNull CommandInput input
    ) {
        String name = input.readString();
        String forced = AuthorityParser.governmentNameIfPrefixed(name);
        if (partyService != null && forced != null) {
            return partyService.partyByName(forced)
                    .map(party -> ArgumentParseResult.success(
                            new NamedAuthority(party.partyUuid(), party.displayName())))
                    .orElseGet(() -> ArgumentParseResult.<NamedAuthority>failure(
                            new IllegalArgumentException("No registered government named '" + forced
                                    + "'. Register it first with /realty government register <name>.")));
        }
        Player onlinePlayer = Bukkit.getPlayerExact(name);
        if (onlinePlayer != null) {
            return ArgumentParseResult.success(
                    new NamedAuthority(onlinePlayer.getUniqueId(), onlinePlayer.getName()));
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayerIfCached(name);
        if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
            return ArgumentParseResult.success(
                    new NamedAuthority(offlinePlayer.getUniqueId(), offlinePlayer.getName()));
        }
        if (partyService != null) {
            var party = partyService.partyByName(name);
            if (party.isPresent()) {
                return ArgumentParseResult.success(
                        new NamedAuthority(party.get().partyUuid(), party.get().displayName()));
            }
        }
        return ArgumentParseResult.failure(
                new IllegalArgumentException("No player or registered government named: " + name));
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
                    suggestions.add(Suggestion.suggestion(PartyService.commandName(party)));
                }
            }
            return CompletableFuture.completedFuture(suggestions);
        };
    }
}
