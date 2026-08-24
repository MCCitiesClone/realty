package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DurationFormatter;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.LandlordSetEvent;
import io.github.md5sha256.realty.api.event.PriceChangedEvent;
import io.github.md5sha256.realty.api.event.PriceSetEvent;
import io.github.md5sha256.realty.api.event.TenantSetEvent;
import io.github.md5sha256.realty.api.event.TitleTransferEvent;
import io.github.md5sha256.realty.api.event.TitleTransferredEvent;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.command.util.NamedAuthority;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyNames;
import io.github.md5sha256.realty.party.PartyService;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.time.Duration;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /realty set …} family: instant changes to a contract's terms.
 *
 * <p>Distinct from {@code /realty modify}, which proposes a change for the other party to
 * accept; these apply immediately and are gated on the corresponding permission.</p>
 */
@Command({"realty", "rl"})
public final class SetCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;
    private final PartyService parties;

    @Inject
    public SetCommands(@NotNull RealtyPaperApi api,
                       @NotNull Message messages,
                       @NotNull RealtyEventDispatch events,
                       @NotNull PartyService parties) {
        this.api = api;
        this.messages = messages;
        this.events = events;
        this.parties = parties;
    }

    @Route("set price <price> [region]")
    @Permission("realty.command.set.price")
    @Description("Set a region's price")
    public void setPrice(@Sender CommandSender sender,
                         @Arg(value = "price", min = 0.01) double price,
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
        if (sender instanceof Player player
                && !this.events.fireSync(new PriceSetEvent(region, player.getUniqueId(), price))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        authorizeLeaseholdSet(sender, region, "realty.command.set.price.others",
                "realty.command.set.price.leasehold", () ->
        this.api.setPrice(regionId, worldId, price).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.SetPriceResult.Success ignored -> {
                        sender.sendMessage(this.messages.component(MessageKeys.SET_PRICE_SUCCESS,
                                "price", CurrencyFormatter.format(price),
                                "region", regionId));
                        this.events.fireSync(new PriceChangedEvent(region, price));
                }
                case RealtyBackend.SetPriceResult.NoContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_PRICE_NO_CONTRACT,
                                "region", regionId));
                case RealtyBackend.SetPriceResult.AuctionExists ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_PRICE_AUCTION_EXISTS,
                                "region", regionId));
                case RealtyBackend.SetPriceResult.OfferPaymentInProgress ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_PRICE_OFFER_PAYMENT_IN_PROGRESS,
                                "region", regionId));
                case RealtyBackend.SetPriceResult.BidPaymentInProgress ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_PRICE_BID_PAYMENT_IN_PROGRESS,
                                "region", regionId));
                case RealtyBackend.SetPriceResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_PRICE_UPDATE_FAILED,
                                "region", regionId));
            }
        }));
    }

    @Route("set duration <duration> [region]")
    @Permission("realty.command.set.duration")
    @Description("Set a leasehold's term length")
    public void setDuration(@Sender CommandSender sender,
                            @Arg("duration") Duration duration,
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
        authorizeLeaseholdSet(sender, region, "realty.command.set.duration.others",
                "realty.command.set.duration.leasehold", () ->
        this.api.setDuration(regionId, worldId, duration.toSeconds()).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.SetDurationResult.Success ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_DURATION_SUCCESS,
                                "duration", DurationFormatter.format(duration),
                                "region", regionId));
                case RealtyBackend.SetDurationResult.NoLeaseholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_DURATION_NO_LEASEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyBackend.SetDurationResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_DURATION_UPDATE_FAILED,
                                "region", regionId));
            }
        }));
    }

    @Route("set landlord <landlord> [region]")
    @Permission("realty.command.set.landlord")
    @Description("Set a leasehold's landlord")
    public void setLandlord(@Sender CommandSender sender,
                            @Arg("landlord") NamedAuthority landlord,
                            @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                UUID landlordId = landlord.uuid();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        authorizeLeaseholdSet(sender, region, "realty.command.set.landlord.others", null, () ->
        this.api.setLandlord(region, landlordId).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.SetLandlordResult.Success success -> {
                        sender.sendMessage(this.messages.component(MessageKeys.SET_LANDLORD_SUCCESS,
                                "landlord", resolveName(landlordId),
                                "region", success.regionId()));
                        this.events.fireSync(new LandlordSetEvent(region, landlordId, success.previousLandlord()));
                }
                case RealtyPaperApi.SetLandlordResult.NoLeaseholdContract noContract ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_LANDLORD_NO_LEASEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.SetLandlordResult.UpdateFailed updateFailed ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_LANDLORD_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.SetLandlordResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_LANDLORD_ERROR,
                                "error", error.message()));
            }
        }));
    }

    @Route("set titleholder <titleholder> [region]")
    @Permission("realty.command.set.titleholder")
    @Description("Set a freehold's title holder")
    public void setTitleHolder(@Sender CommandSender sender,
                               @Arg("titleholder") NamedAuthority titleholder,
                               @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                UUID titleHolderId = titleholder.uuid();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        if (sender instanceof Player player
                && !sender.hasPermission("realty.command.set.titleholder.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.SET_NO_PERMISSION));
            return;
        }
        if (!this.events.fireSync(new TitleTransferEvent(region, titleHolderId))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.setTitleHolder(region, titleHolderId).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.SetTitleHolderResult.Success success -> {
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TITLEHOLDER_SUCCESS,
                                "titleholder", resolveName(titleHolderId),
                                "region", success.regionId()));
                        this.events.fireSync(new TitleTransferredEvent(region, titleHolderId,
                                success.previousTitleHolder()));
                }
                case RealtyPaperApi.SetTitleHolderResult.NoFreeholdContract noContract ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TITLEHOLDER_NO_FREEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.SetTitleHolderResult.UpdateFailed updateFailed ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TITLEHOLDER_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.SetTitleHolderResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TITLEHOLDER_ERROR,
                                "error", error.message()));
            }
        });
    }

    @Route("set tenant <tenant> [region]")
    @Permission("realty.command.set.tenant")
    @Description("Set a leasehold's tenant")
    public void setTenant(@Sender CommandSender sender,
                          @Arg("tenant") NamedAuthority tenant,
                          @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                UUID tenantId = tenant.uuid();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        authorizeLeaseholdSet(sender, region, "realty.command.set.tenant.others", null, () ->
        this.api.setTenant(region, tenantId).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.SetTenantResult.Success success -> {
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TENANT_SUCCESS,
                                "tenant", resolveName(tenantId),
                                "region", success.regionId()));
                        this.events.fireSync(new TenantSetEvent(region, tenantId, success.previousTenant(),
                                success.landlordId()));
                }
                case RealtyPaperApi.SetTenantResult.NoLeaseholdContract noContract ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TENANT_NO_LEASEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.SetTenantResult.UpdateFailed updateFailed ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TENANT_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.SetTenantResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_TENANT_ERROR,
                                "error", error.message()));
            }
        }));
    }

    @Route("set maxextensions <maxextensions> [region]")
    @Permission("realty.command.set.maxextensions")
    @Description("Set how many times a lease may be extended")
    public void setMaxExtensions(@Sender CommandSender sender,
                                 @Arg(value = "maxextensions", min = -1) int maxextensions,
                                 @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                int maxExtensions = maxextensions;
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        authorizeLeaseholdSet(sender, region, "realty.command.set.maxextensions.others",
                "realty.command.set.maxextensions.leasehold", () ->
        this.api.setMaxRenewals(regionId, worldId, maxExtensions).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.SetMaxRenewalsResult.Success ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_MAX_EXTENSIONS_SUCCESS,
                                "maxextensions", maxExtensions < 0 ? "unlimited" : String.valueOf(maxExtensions),
                                "region", regionId));
                case RealtyBackend.SetMaxRenewalsResult.NoLeaseholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_MAX_EXTENSIONS_NO_LEASEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyBackend.SetMaxRenewalsResult.BelowCurrentExtensions(int current) ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_MAX_EXTENSIONS_BELOW_CURRENT,
                                "current", String.valueOf(current),
                                "region", regionId));
                case RealtyBackend.SetMaxRenewalsResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_MAX_EXTENSIONS_UPDATE_FAILED,
                                "region", regionId));
            }
        }));
    }

    @Route("set authority <authority> [region]")
    @Permission("realty.command.set.authority")
    @Description("Set a region's overseeing authority")
    public void setAuthority(@Sender CommandSender sender,
                             @Arg("authority") NamedAuthority authority,
                             @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                UUID authorityId = authority.uuid();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        if (sender instanceof Player player
                && !sender.hasPermission("realty.command.set.authority.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.SET_NO_PERMISSION));
            return;
        }
        this.api.setAuthority(regionId, worldId, authorityId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.SetAuthorityResult.Success ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_AUTHORITY_SUCCESS,
                                "authority", resolveName(authorityId),
                                "region", regionId));
                case RealtyBackend.SetAuthorityResult.NoFreeholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_AUTHORITY_NO_FREEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyBackend.SetAuthorityResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.SET_AUTHORITY_UPDATE_FAILED,
                                "region", regionId));
            }
        });
    }

    private @NotNull String resolveName(@NotNull UUID uuid) {
        return PartyNames.resolve(this.parties, uuid);
    }

    /**
     * Authorizes a leasehold {@code set} mutation, then runs {@code onAuthorized}. Non-players (console)
     * and admins holding {@code bypassPerm} are trusted. For a leasehold the authority is the landlord,
     * not WorldGuard ownership (the WorldGuard owner of an active lease is the tenant): a vacant lease may
     * be set instantly by its landlord, an occupied lease must use {@code /realty modify} so rents cannot
     * be changed mid-tenancy without notice. For a freehold/unregistered region this falls back to the
     * WorldGuard-owner check so title-holder-owned regions keep working.
     *
     * <p>A non-null {@code leaseholdPerm} additionally gates an instant leasehold term change behind that
     * node, so it is refused (pointing at {@code /realty modify}) unless the caller holds it. It is set for
     * the terms {@code /realty modify} also covers (price, duration, max-extensions) and left {@code null}
     * for structural transfers (landlord, tenant), which have no {@code /modify} equivalent.</p>
     */
    private void authorizeLeaseholdSet(@NotNull CommandSender sender, @NotNull WorldGuardRegion region,
                                       @NotNull String bypassPerm, @Nullable String leaseholdPerm,
                                       @NotNull Runnable onAuthorized) {
        if (!(sender instanceof Player player)) {
            onAuthorized.run();
            return;
        }
        if (player.hasPermission(bypassPerm)) {
            onAuthorized.run();
            return;
        }
        String regionId = region.region().getId();
        boolean isWorldGuardOwner = region.region().getOwners().contains(player.getUniqueId());
        this.api.getLeaseholdContract(regionId, region.world().getUID()).thenAccept(lease -> {
            if (lease != null) {
                // Some instant term changes on a leasehold require an extra node; without it the
                // change must go through /realty modify so it applies on the next cycle.
                if (leaseholdPerm != null && !player.hasPermission(leaseholdPerm)) {
                    player.sendMessage(this.messages.component(MessageKeys.SET_LEASEHOLD_NO_PERMISSION,
                            "region", regionId));
                } else if (lease.tenantId() != null) {
                    player.sendMessage(this.messages.component(MessageKeys.SET_OCCUPIED_USE_MODIFY,
                            "region", regionId));
                } else if (!this.parties.actsFor(player.getUniqueId(), lease.landlordId())) {
                    player.sendMessage(this.messages.component(MessageKeys.SET_NOT_LANDLORD,
                            "region", regionId));
                } else {
                    onAuthorized.run();
                }
                return;
            }
            if (isWorldGuardOwner) {
                onAuthorized.run();
            } else {
                player.sendMessage(this.messages.component(MessageKeys.SET_NO_PERMISSION));
            }
        });
    }
}
