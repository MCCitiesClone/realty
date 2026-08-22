package io.github.md5sha256.realty.command.resolver;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Completes {@link TagId} against the tags configured in {@code region-tags.yml}. */
@Singleton
public final class TagIdResolver implements ParameterResolver<TagId> {

    private final AtomicReference<RealtyTags> tags;

    @Inject
    public TagIdResolver(@NotNull AtomicReference<RealtyTags> tags) {
        this.tags = tags;
    }

    @Override
    public @NotNull Class<TagId> type() {
        return TagId.class;
    }

    @Override
    public @NotNull Optional<TagId> resolve(@NotNull String token,
                                            @NotNull CommandSender sender) {
        // An unknown tag is accepted here and reported by the command, which knows whether the
        // operator meant to add one that is not configured.
        return token.isBlank() ? Optional.empty() : Optional.of(new TagId(token));
    }

    @Override
    public @NotNull List<String> suggestions(@NotNull String prefix, @NotNull CommandSender sender) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        // Read through the holder rather than caching: /realty reload swaps the tag set.
        return this.tags.get().tagIds().stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(lowered))
                .sorted()
                .toList();
    }
}
