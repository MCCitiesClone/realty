package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.LeaseExtendEvent;
import io.github.md5sha256.realty.api.event.LeaseExtendedEvent;
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
 * Handles {@code /realty extend [region]}.
 */
@Command({"realty", "rl"})
public final class ExtendCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public ExtendCommand(@NotNull RealtyPaperApi api,
                         @NotNull Message messages,
                         @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("extend [region]")
    @Permission("realty.command.extend")
    @Description("Extend your lease on a region")
    public void extend(@Sender CommandSender rawSender,
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
        if (!this.events.fireSync(new LeaseExtendEvent(region, sender.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.extend(region, sender.getUniqueId()).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.ExtendResult.Success success -> {
                    sender.sendMessage(this.messages.component(MessageKeys.EXTEND_SUCCESS,
                            "region", success.regionId(),
                            "price", CurrencyFormatter.format(success.price())));
                    // Post-event; fireSync hops to the main thread. Available for external listeners.
                    this.events.fireSync(new LeaseExtendedEvent(region, sender.getUniqueId(), success.price()));
                }
                case RealtyPaperApi.ExtendResult.NoLeaseholdContract noContract ->
                        sender.sendMessage(this.messages.component(MessageKeys.EXTEND_NO_LEASEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.ExtendResult.NoExtensionsRemaining noExtensions ->
                        sender.sendMessage(this.messages.component(MessageKeys.EXTEND_NO_EXTENSIONS,
                                "region", noExtensions.regionId()));
                case RealtyPaperApi.ExtendResult.Terminating terminating ->
                        sender.sendMessage(this.messages.component(MessageKeys.EXTEND_TERMINATING,
                                "region", terminating.regionId()));
                case RealtyPaperApi.ExtendResult.InsufficientFunds insufficient ->
                        sender.sendMessage(this.messages.component(MessageKeys.EXTEND_INSUFFICIENT_FUNDS,
                                "price", CurrencyFormatter.format(insufficient.price()),
                                "balance", CurrencyFormatter.format(insufficient.balance())));
                case RealtyPaperApi.ExtendResult.PaymentFailed failed ->
                        sender.sendMessage(this.messages.component(MessageKeys.EXTEND_PAYMENT_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.ExtendResult.UpdateFailed updateFailed ->
                        sender.sendMessage(this.messages.component(MessageKeys.EXTEND_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.ExtendResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.EXTEND_ERROR,
                                "error", error.message()));
            }
        });
    }
}
