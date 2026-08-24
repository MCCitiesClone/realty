package io.github.md5sha256.realty.dialog;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.entity.OccupancyFilter;
import io.github.md5sha256.realty.database.entity.SearchResultEntity;
import io.github.md5sha256.realty.database.mapper.SearchMapper;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.RealtyTags;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.TextComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders a page of search results, and the pagination links that re-run the query.
 *
 * <p>The dialog that collects the criteria lives in {@link SearchDialogHandler}; this is only what
 * happens after the player presses Search, and is also what {@code /realty search results} calls
 * when a pagination link is clicked.</p>
 */
public final class SearchResults {

    static final int PAGE_SIZE = 10;

    private final Database database;
    private final ExecutorState executorState;
    private final AtomicReference<RealtyTags> realtyTags;
    private final Message messages;

    enum TagState {
        IGNORE,
        INCLUDE,
        EXCLUDE;

        TagState next() {
            return switch (this) {
                case IGNORE -> INCLUDE;
                case INCLUDE -> EXCLUDE;
                case EXCLUDE -> IGNORE;
            };
        }
    }


    @Inject
    public SearchResults(@NotNull Database database,
                        @NotNull ExecutorState executorState,
                        @NotNull AtomicReference<RealtyTags> realtyTags,
                        @NotNull Message messages) {
        this.database = database;
        this.executorState = executorState;
        this.realtyTags = realtyTags;
        this.messages = messages;
    }

    /**
     * Executes the search query and sends paginated results to the audience.
     */
    public void performSearch(@NotNull Audience sender,
                       boolean includeFreehold, boolean includeLeasehold,
                       @Nullable Collection<String> tagIds,
                       @Nullable Collection<String> excludedTagIds,
                       double minPrice, double maxPrice,
                       @NotNull OccupancyFilter occupancy, int page) {
        CompletableFuture.runAsync(() -> {
            try (SqlSessionWrapper session = database.openSession(true)) {
                SearchMapper mapper = session.searchMapper();
                int totalCount = mapper.searchCount(includeFreehold, includeLeasehold,
                        tagIds, excludedTagIds, minPrice, maxPrice, occupancy);

                if (totalCount == 0) {
                    sender.sendMessage(messages.component(MessageKeys.SEARCH_NO_RESULTS));
                    return;
                }

                int totalPages = (totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
                if (page > totalPages) {
                    sender.sendMessage(messages.component(MessageKeys.SEARCH_INVALID_PAGE,
                            "page", String.valueOf(page),
                            "total", String.valueOf(totalPages)));
                    return;
                }

                int offset = (page - 1) * PAGE_SIZE;
                List<SearchResultEntity> results = mapper.search(includeFreehold, includeLeasehold,
                        tagIds, excludedTagIds, minPrice, maxPrice, occupancy, PAGE_SIZE, offset);

                TextComponent.Builder builder = Component.text();
                builder.append(messages.component(MessageKeys.SEARCH_HEADER,
                        "count", String.valueOf(totalCount)));

                for (SearchResultEntity result : results) {
                    String typeLabel = "freehold".equals(result.contractType())
                            ? "Freehold" : "Leasehold";
                    builder.appendNewline();
                    builder.append(parseMiniMessage(MessageKeys.SEARCH_ENTRY,
                            "region", result.worldGuardRegionId(),
                            "type", typeLabel,
                            "price", CurrencyFormatter.format(result.price())));
                }

                appendFooter(builder, includeFreehold, includeLeasehold, tagIds, excludedTagIds,
                        minPrice, maxPrice, occupancy, page, totalPages);
                sender.sendMessage(builder.build());
            } catch (Exception ex) {
                sender.sendMessage(messages.component(MessageKeys.SEARCH_ERROR,
                        "error", ex.getMessage()));
            }
        }, executorState.dbExec());
    }

    public static double parsePrice(@Nullable String text, double fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void appendFooter(@NotNull TextComponent.Builder builder,
                              boolean includeFreehold, boolean includeLeasehold,
                              @Nullable Collection<String> tagIds,
                              @Nullable Collection<String> excludedTagIds,
                              double minPrice, double maxPrice,
                              @NotNull OccupancyFilter occupancy,
                              int page, int totalPages) {
        Component previousComponent = page > 1
                ? buildNavComponent(MessageKeys.SEARCH_PREVIOUS, includeFreehold, includeLeasehold,
                tagIds, excludedTagIds, minPrice, maxPrice, occupancy, page - 1)
                : Component.empty();
        Component nextComponent = page < totalPages
                ? buildNavComponent(MessageKeys.SEARCH_NEXT, includeFreehold, includeLeasehold,
                tagIds, excludedTagIds, minPrice, maxPrice, occupancy, page + 1)
                : Component.empty();
        builder.appendNewline()
                .append(messages.component(MessageKeys.SEARCH_FOOTER,
                        "page", String.valueOf(page),
                        "total", String.valueOf(totalPages),
                        "previous", previousComponent,
                        "next", nextComponent));
    }

    private @NotNull Component buildNavComponent(@NotNull String key,
                                                 boolean includeFreehold, boolean includeLeasehold,
                                                 @Nullable Collection<String> tagIds,
                                                 @Nullable Collection<String> excludedTagIds,
                                                 double minPrice, double maxPrice,
                                                 @NotNull OccupancyFilter occupancy,
                                                 int targetPage) {
        StringBuilder command = new StringBuilder("/realty search results");
        if (includeFreehold) {
            command.append(" --freehold");
        }
        if (includeLeasehold) {
            command.append(" --leasehold");
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            command.append(" --tags ").append(String.join(",", tagIds));
        }
        if (excludedTagIds != null && !excludedTagIds.isEmpty()) {
            command.append(" --exclude-tags ").append(String.join(",", excludedTagIds));
        }
        if (minPrice > 0) {
            command.append(" --min-price ").append(minPrice);
        }
        if (maxPrice < Double.MAX_VALUE) {
            command.append(" --max-price ").append(maxPrice);
        }
        if (occupancy != OccupancyFilter.UNOCCUPIED) {
            command.append(" --occupancy ").append(occupancy.name());
        }
        command.append(" --page ").append(targetPage);
        return parseMiniMessage(key, "command", command.toString());
    }

    /**
     * Renders a message whose placeholders sit inside a MiniMessage tag argument — the click
     * targets on the pagination links and result rows.
     *
     * <p>{@code Message.component} rewrites each placeholder into a generated MiniMessage tag and
     * lets a resolver fill it, which cannot reach inside another tag's argument. {@code format}
     * substitutes textually into the pattern first, so the finished string is deserialized here
     * with the click target already in place.</p>
     *
     * <p>Values passed this way are interpolated as markup rather than escaped, so they must be
     * plugin-authored: region ids, formatted prices and commands this class builds, never
     * player-supplied text.</p>
     */
    private @NotNull Component parseMiniMessage(@NotNull String key,
                                                @NotNull Object... replacements) {
        return MiniMessage.miniMessage().deserialize(messages.format(key, replacements));
    }

}
