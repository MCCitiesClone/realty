package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DurationFormatter;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionRentEvent;
import io.github.md5sha256.realty.api.event.RegionRentedEvent;
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
import java.time.Duration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


/**
 * Handles {@code /realty rent [region]}.
 */
@Command({"realty", "rl"})
public final class RentCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public RentCommand(@NotNull RealtyPaperApi api,
                       @NotNull Message messages,
                       @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("rent [region]")
    @Permission("realty.command.rent")
    @Description("Rent a region that is listed for lease")
    public void rent(@Sender CommandSender rawSender,
                     @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        // Declared as CommandSender rather than Player so the console gets Realty's own
        // players-only message; @Sender Player would have the framework reject it with its.
        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        // Cancellable pre-event (main thread); a veto stops the action before the API is called.
        if (!this.events.fireSync(new RegionRentEvent(region, sender.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.rent(region, sender.getUniqueId()).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.RentResult.Success success -> {
                    sender.sendMessage(this.messages.component(MessageKeys.RENT_SUCCESS,
                            "region", success.regionId(),
                            "price", CurrencyFormatter.format(success.price()),
                            "duration", DurationFormatter.format(Duration.ofSeconds(success.durationSeconds()))));
                    // Post-event; fireSync hops to the main thread. RegionNotificationListener notifies the landlord.
                    this.events.fireSync(new RegionRentedEvent(region, sender.getUniqueId(),
                            success.landlordId(), success.price(), success.durationSeconds()));
                }
                case RealtyPaperApi.RentResult.NoLeaseholdContract noContract ->
                        sender.sendMessage(this.messages.component(MessageKeys.RENT_NO_LEASEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.RentResult.AlreadyOccupied occupied ->
                        sender.sendMessage(this.messages.component(MessageKeys.RENT_ALREADY_OCCUPIED,
                                "region", occupied.regionId()));
                case RealtyPaperApi.RentResult.NotAcceptingTenants notAccepting ->
                        sender.sendMessage(this.messages.component(MessageKeys.RENT_NOT_ACCEPTING_TENANTS,
                                "region", notAccepting.regionId()));
                case RealtyPaperApi.RentResult.InsufficientFunds insufficient ->
                        sender.sendMessage(this.messages.component(MessageKeys.RENT_INSUFFICIENT_FUNDS,
                                "price", CurrencyFormatter.format(insufficient.price()),
                                "balance", CurrencyFormatter.format(insufficient.balance())));
                case RealtyPaperApi.RentResult.PaymentFailed failed ->
                        sender.sendMessage(this.messages.component(MessageKeys.RENT_PAYMENT_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.RentResult.UpdateFailed updateFailed ->
                        sender.sendMessage(this.messages.component(MessageKeys.RENT_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.RentResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.RENT_ERROR,
                                "error", error.message()));
            }
        });
    }
}
