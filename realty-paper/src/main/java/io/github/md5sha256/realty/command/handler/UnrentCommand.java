package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionUnrentEvent;
import io.github.md5sha256.realty.api.event.RegionUnrentedEvent;
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
 * Handles {@code /realty unrent [region]}.
 */
@Command({"realty", "rl"})
public final class UnrentCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public UnrentCommand(@NotNull RealtyPaperApi api,
                         @NotNull Message messages,
                         @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("unrent [region]")
    @Permission("realty.command.unrent")
    @Description("End your tenancy of a region")
    public void unrent(@Sender CommandSender rawSender,
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
        if (!region.region().getOwners().contains(sender.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.UNRENT_NOT_TENANT,
                    "region", region.region().getId()));
            return;
        }
        // Cancellable pre-event (main thread); a veto stops the action before the API is called.
        if (!this.events.fireSync(new RegionUnrentEvent(region, sender.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.unrent(region, sender.getUniqueId()).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.UnrentResult.Success success -> {
                    sender.sendMessage(this.messages.component(MessageKeys.UNRENT_SUCCESS,
                            "region", success.regionId(),
                            "refund", CurrencyFormatter.format(success.refund())));
                    // Post-event; fireSync hops to the main thread. RegionNotificationListener notifies the landlord.
                    this.events.fireSync(new RegionUnrentedEvent(region, sender.getUniqueId(),
                            success.landlordId(), success.refund()));
                }
                case RealtyPaperApi.UnrentResult.NoLeaseholdContract noContract ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNRENT_NO_LEASEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.UnrentResult.RefundFailed failed ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNRENT_REFUND_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.UnrentResult.UpdateFailed updateFailed ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNRENT_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.UnrentResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.UNRENT_ERROR,
                                "error", error.message()));
            }
        });
    }
}
