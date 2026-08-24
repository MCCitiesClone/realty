package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code /realty unset …} family: clearing a price, title holder or tenant. */
@Command({"realty", "rl"})
public final class UnsetCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;

    @Inject
    public UnsetCommands(@NotNull RealtyPaperApi api,
                         @NotNull Message messages) {
        this.api = api;
        this.messages = messages;
    }

    @Route("unset price [region]")
    @Permission("realty.command.unset.price")
    @Description("Clear a region's listed price")
    public void unsetPrice(@Sender CommandSender sender,
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
                && !sender.hasPermission("realty.command.unset.price.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.UNSET_NO_PERMISSION));
            return;
        }
        this.api.unsetPrice(regionId, worldId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.UnsetPriceResult.Success ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_PRICE_SUCCESS,
                                "region", regionId));
                case RealtyBackend.UnsetPriceResult.NoFreeholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_PRICE_NO_FREEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyBackend.UnsetPriceResult.OfferPaymentInProgress ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_PRICE_OFFER_PAYMENT_IN_PROGRESS,
                                "region", regionId));
                case RealtyBackend.UnsetPriceResult.BidPaymentInProgress ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_PRICE_BID_PAYMENT_IN_PROGRESS,
                                "region", regionId));
                case RealtyBackend.UnsetPriceResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_PRICE_UPDATE_FAILED,
                                "region", regionId));
            }
        });
    }

    @Route("unset titleholder [region]")
    @Permission("realty.command.unset.titleholder")
    @Description("Clear a freehold's title holder")
    public void unsetTitleHolder(@Sender CommandSender sender,
                          @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        if (sender instanceof Player player
                && !sender.hasPermission("realty.command.unset.titleholder.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.UNSET_NO_PERMISSION));
            return;
        }
        this.api.setTitleHolder(region, null).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.SetTitleHolderResult.Success ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TITLEHOLDER_SUCCESS,
                                "region", regionId));
                case RealtyPaperApi.SetTitleHolderResult.NoFreeholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TITLEHOLDER_NO_FREEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyPaperApi.SetTitleHolderResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TITLEHOLDER_UPDATE_FAILED,
                                "region", regionId));
                case RealtyPaperApi.SetTitleHolderResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TITLEHOLDER_ERROR,
                                "error", error.message()));
            }
        });
    }

    @Route("unset tenant [region]")
    @Permission("realty.command.unset.tenant")
    @Description("Clear a leasehold's tenant")
    public void unsetTenant(@Sender CommandSender sender,
                          @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        if (sender instanceof Player player
                && !sender.hasPermission("realty.command.unset.tenant.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.UNSET_NO_PERMISSION));
            return;
        }
        this.api.setTenant(region, null).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.SetTenantResult.Success ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TENANT_SUCCESS,
                                "region", regionId));
                case RealtyPaperApi.SetTenantResult.NoLeaseholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TENANT_NO_LEASEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyPaperApi.SetTenantResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TENANT_UPDATE_FAILED,
                                "region", regionId));
                case RealtyPaperApi.SetTenantResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNSET_TENANT_ERROR,
                                "error", error.message()));
            }
        });
    }
}
