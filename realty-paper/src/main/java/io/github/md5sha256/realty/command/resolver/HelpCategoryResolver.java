package io.github.md5sha256.realty.command.resolver;

import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Completes {@code /realty help <category>}. */
public final class HelpCategoryResolver implements ParameterResolver<HelpCategory> {

    /**
     * The categories offered as suggestions.
     *
     * <p>{@code admin} is deliberately absent: it is a valid category and resolves fine, it is
     * just not advertised to players who would not be able to use most of what it lists.</p>
     */
    private static final List<String> SUGGESTED =
            List.of("basics", "management", "offers", "auctions");

    @Override
    public @NotNull Class<HelpCategory> type() {
        return HelpCategory.class;
    }

    @Override
    public @NotNull Optional<HelpCategory> resolve(@NotNull String token,
                                                   @NotNull CommandSender sender) {
        // An unknown category is accepted and answered by the command, which has a message for it.
        return token.isBlank() ? Optional.empty() : Optional.of(new HelpCategory(token));
    }

    @Override
    public @NotNull List<String> suggestions(@NotNull String prefix, @NotNull CommandSender sender) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return SUGGESTED.stream().filter(c -> c.startsWith(lowered)).sorted().toList();
    }
}
