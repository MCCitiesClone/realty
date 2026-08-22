package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.minecraftcitiesnetwork.pluginInfrastructure.util.DateFormatter;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DurationFormatter;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.command.util.GroupPrefix;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.entity.FreeholdContractEntity;
import io.github.md5sha256.realty.database.entity.LeaseholdContractEntity;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyNames;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.settings.ConfigRegionTag;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.github.md5sha256.realty.settings.Settings;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Handles {@code /realty info [region]}. */
@Command({"realty", "rl"})
public final class InfoCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final AtomicReference<Settings> settings;
    private final Database database;
    private final AtomicReference<RealtyTags> realtyTags;
    private final Message messages;
    private final PartyService parties;

    @Inject
    public InfoCommand(@NotNull RealtyPaperApi api,
                       @NotNull AtomicReference<Settings> settings,
                       @NotNull Database database,
                       @NotNull AtomicReference<RealtyTags> realtyTags,
                       @NotNull Message messages,
                       @NotNull PartyService parties) {
        this.api = api;
        this.settings = settings;
        this.database = database;
        this.realtyTags = realtyTags;
        this.messages = messages;
        this.parties = parties;
    }

    private static @NotNull String resolveMembers(@NotNull WorldGuardRegion region) {
        Set<UUID> memberUuids = region.region().getMembers().getUniqueIds();
        Set<String> memberGroups = region.region().getMembers().getGroups();
        if (memberUuids.isEmpty() && memberGroups.isEmpty()) {
            return "None";
        }
        // Always players: a government holding the region is expanded into its members before
        // it reaches a WorldGuard domain, so there is no party to resolve here.
        String members = memberUuids.stream()
                .map(PartyNames::playerName)
                .collect(Collectors.joining(", "));
        String groups = memberGroups.stream()
                // The typeable form: /realty add g:name is rejected by the client (see GroupPrefix).
                .map(g -> GroupPrefix.GROUP_PREFIX + g)
                .collect(Collectors.joining(", "));
        if (!members.isEmpty() && !groups.isEmpty()) {
            return members + ", " + groups;
        } else if (!members.isEmpty()) {
            return members;
        } else {
            return groups;
        }
    }

    private @NotNull String resolveName(@NotNull UUID uuid) {
        return PartyNames.resolve(parties, uuid);
    }

    @Route("info [region]")
    @Permission("realty.command.info")
    @Description("Show a region's Realty details")
    public void info(@Sender CommandSender sender,
                     @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        // resolveMembers reads WG data and must run on the main thread
        String membersStr = resolveMembers(region);

        this.api.getRegionInfo(regionId, worldId).thenAccept(info -> {
            TextComponent.Builder builder = Component.text();
            builder.append(this.messages.component(MessageKeys.INFO_HEADER,
                    "region", regionId));

            FreeholdContractEntity freehold = info.freehold();
            LeaseholdContractEntity leasehold = info.leasehold();
            boolean hasAuction = info.auction() != null;

            if (freehold == null && leasehold == null && !hasAuction) {
                builder.appendNewline()
                        .append(this.messages.component(MessageKeys.INFO_NO_CONTRACTS));
                sender.sendMessage(builder.build());
                return;
            }

            if (freehold != null) {
                appendFreeholdInfo(builder, freehold, info.lastSoldPrice(), membersStr);
                builder.appendNewline()
                        .append(this.messages.component(MessageKeys.INFO_AUCTION_ACTIVE,
                                "has_auction", hasAuction ? "Yes" : "No"));
            }

            if (leasehold != null) {
                appendLeaseholdInfo(builder, leasehold, membersStr);
            }

            try (SqlSessionWrapper session = this.database.openSession(true)) {
                List<String> tags = session.regionTagMapper().selectTagIdsByRegionId(regionId);
                Component tagsComponent = buildTagsComponent(tags);
                builder.appendNewline()
                        .append(this.messages.component(MessageKeys.INFO_TAGS,
                                "tags", tagsComponent));
            }

            sender.sendMessage(builder.build());
        }).exceptionally(ex -> {
            ex.printStackTrace();
            sender.sendMessage(this.messages.component(MessageKeys.INFO_ERROR,
                    "error", String.valueOf(ex.getMessage())));
            return null;
        });
    }


    private void appendFreeholdInfo(@NotNull TextComponent.Builder builder,
                                @NotNull FreeholdContractEntity freehold,
                                @Nullable Double lastSoldPrice,
                                @NotNull String membersStr) {
        String titleHolder = freehold.titleHolderId() != null ? resolveName(freehold.titleHolderId()) : "N/A";
        String authority = resolveName(freehold.authorityId());

        if (freehold.price() != null) {
            builder.appendNewline()
                    .append(this.messages.component(MessageKeys.INFO_FOR_SALE,
                            "title_holder", titleHolder,
                            "authority", authority,
                            "price", CurrencyFormatter.format(freehold.price())));
        } else {
            String lastSold = lastSoldPrice != null ? CurrencyFormatter.format(lastSoldPrice) : "N/A";
            builder.appendNewline()
                    .append(this.messages.component(MessageKeys.INFO_SOLD,
                            "title_holder", titleHolder,
                            "members", membersStr,
                            "authority", authority,
                            "last_sold_price", lastSold));
        }
    }

    private @NotNull Component buildTagsComponent(@NotNull List<String> tagIds) {
        if (tagIds.isEmpty()) {
            return Component.text("None");
        }
        RealtyTags tags = this.realtyTags.get();
        TextComponent.Builder tagsBuilder = Component.text();
        for (int i = 0; i < tagIds.size(); i++) {
            if (i > 0) {
                tagsBuilder.append(Component.text(", "));
            }
            String tagId = tagIds.get(i);
            ConfigRegionTag configTag = tags.get(tagId);
            tagsBuilder.append(configTag != null ? configTag.tagDisplayName() : Component.text(tagId));
        }
        return tagsBuilder.build();
    }

    private void appendLeaseholdInfo(@NotNull TextComponent.Builder builder,
                                     @NotNull LeaseholdContractEntity leasehold,
                                     @NotNull String membersStr) {
        String tenant = leasehold.tenantId() != null ? resolveName(leasehold.tenantId()) : "N/A";
        String extensions;
        if (leasehold.maxExtensions() != null) {
            extensions = leasehold.currentMaxExtensions() + "/" + leasehold.maxExtensions();
        } else {
            extensions = "unlimited";
        }

        builder.appendNewline()
                .append(this.messages.component(MessageKeys.INFO_LEASEHOLD,
                        "landlord", resolveName(leasehold.landlordId()),
                        "members", membersStr,
                        "tenant", tenant,
                        "price", CurrencyFormatter.format(leasehold.price()),
                        "duration", DurationFormatter.format(Duration.ofSeconds(leasehold.durationSeconds())),
                        "start_date", leasehold.startDate() != null
                                ? DateFormatter.format(this.settings.get().dateFormat(), leasehold.startDate())
                                : "N/A",
                        "end_date", leasehold.endDate() != null
                                ? DateFormatter.format(this.settings.get().dateFormat(), leasehold.endDate())
                                : "N/A",
                        "time_left", DurationFormatter.formatTimeLeft(leasehold.endDate()),
                        "extensions", extensions));
    }
}
