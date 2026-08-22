package io.github.md5sha256.realty.command;

import com.minecraftcitiesnetwork.pluginInfrastructure.util.DateFormatter;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DurationFormatter;
import io.github.md5sha256.realty.api.HistoryEventType;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.command.util.AuthorityParser;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.command.util.DurationParser;
import io.github.md5sha256.realty.command.util.LeaseholdChangeSummary;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.util.WorldGuardRegionResolver;
import io.github.md5sha256.realty.database.entity.HistoryEntry;
import io.github.md5sha256.realty.party.PartyNames;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.paper.util.sender.Source;
import org.incendo.cloud.parser.flag.CommandFlag;
import org.incendo.cloud.parser.standard.EnumParser;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles {@code /realty history <region> [--event <type>] [--time <duration>] [--player <name>] [--page <n>]}.
 *
 * <p>Permission: {@code realty.command.history}.</p>
 */
public record HistoryCommand(@NotNull RealtyPaperApi api,
                              @NotNull AtomicReference<Settings> settings,
                              @NotNull Message messages,
                              @NotNull PartyService parties) implements CustomCommandBean.Single {

    private static final int PAGE_SIZE = 10;

    private static final Map<String, String> EVENT_TYPE_MESSAGE_KEYS = Map.ofEntries(
            Map.entry("BUY", MessageKeys.HISTORY_EVENT_BUY),
            Map.entry("AUCTION_BUY", MessageKeys.HISTORY_EVENT_AUCTION_BUY),
            Map.entry("OFFER_BUY", MessageKeys.HISTORY_EVENT_OFFER_BUY),
            Map.entry("AGENT_ADD", MessageKeys.HISTORY_EVENT_AGENT_ADD),
            Map.entry("AGENT_REMOVE", MessageKeys.HISTORY_EVENT_AGENT_REMOVE),
            Map.entry("RENT", MessageKeys.HISTORY_EVENT_RENT),
            Map.entry("UNRENT", MessageKeys.HISTORY_EVENT_UNRENT),
            Map.entry("RENEW", MessageKeys.HISTORY_EVENT_RENEW),
            Map.entry("LEASEHOLD_EXPIRY", MessageKeys.HISTORY_EVENT_LEASEHOLD_EXPIRY),
            Map.entry("SET_PRICE", MessageKeys.HISTORY_EVENT_SET_PRICE_FREEHOLD),
            Map.entry("UNSET_PRICE", MessageKeys.HISTORY_EVENT_UNSET_PRICE),
            Map.entry("SET_TITLEHOLDER", MessageKeys.HISTORY_EVENT_SET_TITLEHOLDER),
            Map.entry("UNSET_TITLEHOLDER", MessageKeys.HISTORY_EVENT_UNSET_TITLEHOLDER),
            Map.entry("SET_DURATION", MessageKeys.HISTORY_EVENT_SET_DURATION),
            Map.entry("SET_LANDLORD", MessageKeys.HISTORY_EVENT_SET_LANDLORD),
            Map.entry("SET_TENANT", MessageKeys.HISTORY_EVENT_SET_TENANT),
            Map.entry("UNSET_TENANT", MessageKeys.HISTORY_EVENT_UNSET_TENANT),
            Map.entry("SET_MAX_EXTENSIONS", MessageKeys.HISTORY_EVENT_SET_MAX_EXTENSIONS)
    );

    /** Event types where leasehold history uses a different message key than freehold for the same name. */
    private static final Map<String, String> LEASEHOLD_EVENT_MESSAGE_KEYS = Map.of(
            "SET_PRICE", MessageKeys.HISTORY_EVENT_SET_PRICE_LEASEHOLD
    );

    private static final CommandFlag<HistoryEventType> EVENT_FLAG =
            CommandFlag.<Source>builder("event")
                    .withComponent(EnumParser.enumParser(HistoryEventType.class))
                    .build();

    private static final CommandFlag<Duration> TIME_FLAG =
            CommandFlag.<Source>builder("time")
                    .withComponent(DurationParser.duration())
                    .build();

    // Built per registration rather than held as a constant — see CreateCommand.
    private static final String PLAYER_FLAG = "player";

    private @NotNull CommandFlag<UUID> playerFlag() {
        return CommandFlag.<Source>builder(PLAYER_FLAG)
                .withComponent(AuthorityParser.party(parties))
                .build();
    }

    private static final CommandFlag<Integer> PAGE_FLAG =
            CommandFlag.<Source>builder("page")
                    .withComponent(IntegerParser.integerParser(1))
                    .build();

    @Override
    public @NotNull Command<? extends Source> command(@NotNull Command.Builder<Source> builder) {
        return builder
                .literal("history")
                .permission("realty.command.history")
                .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                .flag(EVENT_FLAG)
                .flag(TIME_FLAG)
                .flag(playerFlag())
                .flag(PAGE_FLAG)
                .handler(this::execute)
                .build();
    }

    private void execute(@NotNull CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> sender instanceof Player player
                        ? WorldGuardRegionResolver.resolveAtLocation(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();

        HistoryEventType eventType = ctx.flags().getValue(EVENT_FLAG, null);
        Duration timeDuration = ctx.flags().getValue(TIME_FLAG, null);
        UUID playerId = ctx.flags().getValue(PLAYER_FLAG, null);
        int page = ctx.flags().getValue(PAGE_FLAG, 1);

        String eventTypeStr = eventType != null ? eventType.name() : null;
        LocalDateTime since = timeDuration != null ? LocalDateTime.now().minus(timeDuration) : null;
        int offset = (page - 1) * PAGE_SIZE;

        api.searchHistory(regionId, worldId, eventTypeStr, since, playerId, PAGE_SIZE, offset)
                .thenAccept(result -> {
                    int totalCount = result.totalCount();
                    if (totalCount == 0) {
                        sender.sendMessage(messages.component(MessageKeys.HISTORY_NO_RESULTS,
                                "region", regionId));
                        return;
                    }

                    int totalPages = (totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
                    if (page > totalPages) {
                        sender.sendMessage(messages.component(MessageKeys.HISTORY_INVALID_PAGE,
                                "page", String.valueOf(page),
                                "total", String.valueOf(totalPages)));
                        return;
                    }

                    TextComponent.Builder builder = Component.text();
                    builder.append(messages.component(MessageKeys.HISTORY_HEADER,
                            "region", regionId));

                    for (HistoryEntry entry : result.entries()) {
                        builder.appendNewline();
                        switch (entry) {
                            case HistoryEntry.Freehold freehold -> {
                                String messageKey = resolveEventMessageKey(freehold.eventType());
                                builder.append(
                                        messages.component(messageKey,
                                                "time", DateFormatter.format(settings.get().dateFormat(), freehold.eventTime()),
                                                "buyer", resolveName(freehold.buyerId()),
                                                "authority", resolveName(freehold.authorityId()),
                                                "price", CurrencyFormatter.format(freehold.price())));
                            }
                            case HistoryEntry.Agent agent -> {
                                String messageKey = resolveEventMessageKey(agent.eventType());
                                builder.append(
                                        messages.component(messageKey,
                                                "time", DateFormatter.format(settings.get().dateFormat(), agent.eventTime()),
                                                "agent", resolveName(agent.agentId()),
                                                "actor", resolveName(agent.actorId())));
                            }
                            case HistoryEntry.Leasehold lease -> {
                                String messageKey = resolveLeaseholdEventMessageKey(lease.eventType());
                                builder.append(
                                        messages.component(messageKey,
                                                "time", DateFormatter.format(settings.get().dateFormat(), lease.eventTime()),
                                                "tenant", resolveName(lease.tenantId()),
                                                "landlord", resolveName(lease.landlordId()),
                                                "price", lease.price() != null ? CurrencyFormatter.format(lease.price()) : "N/A",
                                                // <changes> labels extensionsRemaining as "Max Extensions", which only
                                                // holds true for the modification-proposal events (MODIFY_PROPOSE/ACCEPT/
                                                // REJECT/WITHDRAW store the proposed cap there). RENEW/MODIFY_APPLY store
                                                // the remaining count instead, so their templates use <price>, not <changes>.
                                                "changes", LeaseholdChangeSummary.render(
                                                        messages, lease.price(), lease.durationSeconds(),
                                                        lease.extensionsRemaining())));
                            }
                        }
                    }

                    appendFooter(builder, regionId, eventType, timeDuration, playerId, page, totalPages);
                    sender.sendMessage(builder.build());
                }).exceptionally(ex -> {
                    ex.printStackTrace();
                    sender.sendMessage(messages.component(MessageKeys.HISTORY_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    }

    private void appendFooter(@NotNull TextComponent.Builder builder, @NotNull String regionId,
                               @Nullable HistoryEventType eventType, @Nullable Duration timeDuration,
                               @Nullable UUID playerId, int page, int totalPages) {
        Component previousComponent = page > 1
                ? buildNavComponent(MessageKeys.HISTORY_PREVIOUS, regionId, eventType, timeDuration, playerId, page - 1)
                : Component.empty();
        Component nextComponent = page < totalPages
                ? buildNavComponent(MessageKeys.HISTORY_NEXT, regionId, eventType, timeDuration, playerId, page + 1)
                : Component.empty();
        builder.appendNewline()
                .append(messages.component(MessageKeys.HISTORY_FOOTER,
                        "page", String.valueOf(page),
                        "total", String.valueOf(totalPages),
                        "previous", previousComponent,
                        "next", nextComponent));
    }

    private @NotNull Component buildNavComponent(@NotNull String key, @NotNull String regionId,
                                                  @Nullable HistoryEventType eventType,
                                                  @Nullable Duration timeDuration,
                                                  @Nullable UUID playerId, int targetPage) {
        StringBuilder command = new StringBuilder("/realty history ").append(regionId);
        if (eventType != null) {
            command.append(" --event ").append(eventType.name());
        }
        if (timeDuration != null) {
            command.append(" --time ").append(DurationFormatter.formatCompact(timeDuration));
        }
        if (playerId != null) {
            String name = resolveName(playerId);
            command.append(" --player ").append(name);
        }
        command.append(" --page ").append(targetPage);
        // The command goes inside a <click:run_command:...> argument, which no resolver can fill;
        // format() substitutes into the pattern before it is deserialized.
        return MiniMessage.miniMessage().deserialize(
                messages.format(key, "command", command.toString()));
    }

    private static @NotNull String resolveEventMessageKey(@NotNull String eventType) {
        String key = EVENT_TYPE_MESSAGE_KEYS.get(eventType);
        return key != null ? key : deriveEventMessageKey(eventType);
    }

    /**
     * Derives the {@code history.event.<lower-kebab>} message key from an event name, so a new
     * {@link HistoryEventType} renders without a manual map entry. Only naming-convention exceptions
     * (e.g. {@code SET_PRICE}, which distinguishes freehold vs. leasehold) need registering above.
     */
    private static @NotNull String deriveEventMessageKey(@NotNull String eventType) {
        return "history.event." + eventType.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static @NotNull String resolveLeaseholdEventMessageKey(@NotNull String eventType) {
        return LEASEHOLD_EVENT_MESSAGE_KEYS.getOrDefault(eventType, resolveEventMessageKey(eventType));
    }

    private @NotNull String resolveName(@NotNull UUID uuid) {
        return PartyNames.resolve(parties, uuid);
    }

}
