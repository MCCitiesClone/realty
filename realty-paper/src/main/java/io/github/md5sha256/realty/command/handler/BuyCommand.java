package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionBoughtEvent;
import io.github.md5sha256.realty.api.event.RegionBuyEvent;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Handles {@code /realty buy [region]}.
 *
 * <p>Performs a fixed-price purchase at the listed price without requiring approval from the
 * current title holder.</p>
 */
@Command({"realty", "rl"})
public final class BuyCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public BuyCommand(@NotNull RealtyPaperApi api,
                      @NotNull Message messages,
                      @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("buy [region]")
    @Permission("realty.command.buy")
    @Description("Buy a region that is listed for sale")
    public void buy(@Sender CommandSender rawSender,
                    @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        // Declared as CommandSender rather than Player so the console gets Realty's own
        // players-only message; @Sender Player would have the framework reject it with its.
        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region =
                WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        // Cancellable pre-event (main thread); a veto stops the action before the API is called.
        if (!this.events.fireSync(new RegionBuyEvent(region, sender.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.buy(region, sender.getUniqueId()).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.BuyResult.Success success -> {
                    sender.sendMessage(this.messages.component(MessageKeys.BUY_SUCCESS,
                            "price", CurrencyFormatter.format(success.price()),
                            "region", success.regionId()));
                    // Post-event; fireSync hops to the main thread. RegionNotificationListener
                    // notifies the seller.
                    this.events.fireSync(new RegionBoughtEvent(region, sender.getUniqueId(),
                            success.previousTitleHolderId(), success.price()));
                }
                case RealtyPaperApi.BuyResult.NoFreeholdContract noContract ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.BUY_NO_FREEHOLD_CONTRACT, "region", noContract.regionId()));
                case RealtyPaperApi.BuyResult.NotForSale notForSale ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.BUY_NOT_FOR_SALE, "region", notForSale.regionId()));
                case RealtyPaperApi.BuyResult.IsAuthority ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.BUY_IS_AUTHORITY));
                case RealtyPaperApi.BuyResult.IsTitleHolder ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.BUY_IS_TITLE_HOLDER));
                case RealtyPaperApi.BuyResult.InsufficientFunds insufficient ->
                        sender.sendMessage(this.messages.component(MessageKeys.BUY_INSUFFICIENT_FUNDS,
                                "price", CurrencyFormatter.format(insufficient.price()),
                                "balance", CurrencyFormatter.format(insufficient.balance())));
                case RealtyPaperApi.BuyResult.PaymentFailed failed ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.BUY_PAYMENT_FAILED, "error", failed.error()));
                case RealtyPaperApi.BuyResult.TransferFailed transferFailed ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.BUY_TRANSFER_FAILED, "region", transferFailed.regionId()));
                case RealtyPaperApi.BuyResult.Error error ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.BUY_ERROR, "error", error.message()));
            }
        });
    }
}
