package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.minecraftcitiesnetwork.pluginInfrastructure.util.DateFormatter;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DurationFormatter;
import io.github.md5sha256.realty.api.HistoryEventType;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.command.util.LeaseholdChangeSummary;
import io.github.md5sha256.realty.command.util.NamedAuthority;
import io.github.md5sha256.realty.database.entity.HistoryEntry;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyNames;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.settings.Settings;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles {@code /realty history [region] [--event] [--time] [--player] [--page]}.
 */
@Command({"realty", "rl"})
public final class HistoryCommand implements CommandHandler {

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

    private final RealtyPaperApi api;
    private final AtomicReference<Settings> settings;
    private final Message messages;
    private final PartyService parties;

    @Inject
    public HistoryCommand(@NotNull RealtyPaperApi api,
                          @NotNull AtomicReference<Settings> settings,
                          @NotNull Message messages,
                          @NotNull PartyService parties) {
        this.api = api;
        this.settings = settings;
        this.messages = messages;
        this.parties = parties;
    }

    @Route("history [region]")
    @Permission("realty.command.history")
    @Description("Show a region's history")
    public void history(@Sender CommandSender sender,
                        @OptionalArg("region") @Nullable WorldGuardRegion namedRegion,
                        @Flag("event") @Nullable HistoryEventType eventType,
                        @Flag("time") @Nullable Duration timeDuration,
                        @Flag("player") @Nullable NamedAuthority player,
                        @Flag(value = "page", defaultValue = "1", min = 1) int page) {
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player standing
                        ? WorldGuardRegionResolver.regionAt(standing.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();

        UUID playerId = player == null ? null : player.uuid();

        String eventTypeStr = eventType != null ? eventType.name() : null;
        LocalDateTime since = timeDuration != null ? LocalDateTime.now().minus(timeDuration) : null;
        int offset = (page - 1) * PAGE_SIZE;

        this.api.searchHistory(regionId, worldId, eventTypeStr, since, playerId, PAGE_SIZE, offset)
                .thenAccept(result -> {
                    int totalCount = result.totalCount();
                    if (totalCount == 0) {
                        sender.sendMessage(this.messages.component(MessageKeys.HISTORY_NO_RESULTS,
                                "region", regionId));
                        return;
                    }

                    int totalPages = (totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
                    if (page > totalPages) {
                        sender.sendMessage(this.messages.component(MessageKeys.HISTORY_INVALID_PAGE,
                                "page", String.valueOf(page),
                                "total", String.valueOf(totalPages)));
                        return;
                    }

                    TextComponent.Builder builder = Component.text();
                    builder.append(this.messages.component(MessageKeys.HISTORY_HEADER,
                            "region", regionId));

                    for (HistoryEntry entry : result.entries()) {
                        builder.appendNewline();
                        switch (entry) {
                            case HistoryEntry.Freehold freehold -> {
                                String messageKey = resolveEventMessageKey(freehold.eventType());
                                builder.append(
                                        this.messages.component(messageKey,
                                                "time", DateFormatter.format(this.settings.get().dateFormat(), freehold.eventTime()),
                                                "buyer", resolveName(freehold.buyerId()),
                                                "authority", resolveName(freehold.authorityId()),
                                                "price", CurrencyFormatter.format(freehold.price())));
                            }
                            case HistoryEntry.Agent agent -> {
                                String messageKey = resolveEventMessageKey(agent.eventType());
                                builder.append(
                                        this.messages.component(messageKey,
                                                "time", DateFormatter.format(this.settings.get().dateFormat(), agent.eventTime()),
                                                "agent", resolveName(agent.agentId()),
                                                "actor", resolveName(agent.actorId())));
                            }
                            case HistoryEntry.Leasehold lease -> {
                                String messageKey = resolveLeaseholdEventMessageKey(lease.eventType());
                                builder.append(
                                        this.messages.component(messageKey,
                                                "time", DateFormatter.format(this.settings.get().dateFormat(), lease.eventTime()),
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
                    sender.sendMessage(this.messages.component(MessageKeys.HISTORY_ERROR,
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
                .append(this.messages.component(MessageKeys.HISTORY_FOOTER,
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
                this.messages.format(key, "command", command.toString()));
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
        return PartyNames.resolve(this.parties, uuid);
    }
}
