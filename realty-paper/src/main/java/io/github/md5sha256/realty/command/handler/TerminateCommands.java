package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DateTimeFormatters;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.LeaseTerminateEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminationCancelledEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminationScheduledEvent;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /realty terminate …} family: scheduling the end of a tenancy and cancelling it.
 *
 * <p>{@code --now} skips the notice period, which is otherwise always honoured.</p>
 */
@Command({"realty", "rl"})
public final class TerminateCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public TerminateCommands(@NotNull RealtyPaperApi api,
                             @NotNull Message messages,
                             @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("terminate [region]")
    @Permission("realty.command.terminate")
    @Description("Schedule the end of a tenancy")
    public void terminate(@Sender CommandSender rawSender,
                          @OptionalArg("region") @Nullable WorldGuardRegion namedRegion,
                          @Flag(value = "now", presence = true) boolean now) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        boolean immediate = now;
        if (immediate && !sender.hasPermission("realty.command.terminate.now")) {
            sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_NOW_NO_PERMISSION));
            return;
        }
        // Cancellable pre-event (main thread); a veto stops the action before the API is called.
        if (!this.events.fireSync(new LeaseTerminateEvent(region, sender.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        boolean bypass = sender.hasPermission("realty.command.terminate.others");
        String regionId = region.region().getId();
        this.api.terminate(region, sender.getUniqueId(), bypass, immediate).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.TerminateResult.Success success -> {
                    String date = success.effectiveDate().format(DateTimeFormatters.DATE_TIME);
                    if (success.charged() > 0) {
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_SUCCESS_CHARGED,
                                "region", success.regionId(),
                                "date", date,
                                "charged", CurrencyFormatter.format(success.charged())));
                    } else {
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_SUCCESS,
                                "region", success.regionId(),
                                "date", date));
                    }
                    this.events.fireSync(new LeaseTerminationScheduledEvent(region, success.landlordId(),
                            success.tenantId(), success.terminatedByRole(), success.effectiveDate(),
                            success.charged()));
                }
                case RealtyPaperApi.TerminateResult.NoLeaseholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_NO_LEASEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyPaperApi.TerminateResult.NotOccupied ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_NOT_OCCUPIED,
                                "region", regionId));
                case RealtyPaperApi.TerminateResult.AlreadyTerminating ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_ALREADY_TERMINATING,
                                "region", regionId));
                case RealtyPaperApi.TerminateResult.NotAuthorized ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_NOT_AUTHORIZED,
                                "region", regionId));
                case RealtyPaperApi.TerminateResult.InsufficientFunds funds ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_INSUFFICIENT_FUNDS,
                                "price", CurrencyFormatter.format(funds.price()),
                                "balance", CurrencyFormatter.format(funds.balance())));
                case RealtyPaperApi.TerminateResult.PaymentFailed failed ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_PAYMENT_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.TerminateResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_UPDATE_FAILED,
                                "region", regionId));
                case RealtyPaperApi.TerminateResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_ERROR,
                                "error", error.message()));
            }
        });
    
    }

    @Route("terminate cancel [region]")
    @Permission("realty.command.terminate")
    @Description("Cancel a scheduled termination")
    public void cancel(@Sender CommandSender rawSender,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        boolean bypass = sender.hasPermission("realty.command.terminate.others");
        String regionId = region.region().getId();
        this.api.cancelTermination(regionId, region.world().getUID(), sender.getUniqueId(), bypass)
                .thenAccept(result -> {
                    switch (result) {
                        case io.github.md5sha256.realty.api.RealtyBackend.CancelTerminationResult.Success success -> {
                            sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_CANCEL_SUCCESS,
                                    "region", regionId));
                            this.events.fireSync(new LeaseTerminationCancelledEvent(region, success.landlordId(),
                                    success.tenantId(), success.terminatedByRole()));
                        }
                        case io.github.md5sha256.realty.api.RealtyBackend.CancelTerminationResult.NoLeaseholdContract ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_CANCEL_NO_LEASEHOLD_CONTRACT,
                                        "region", regionId));
                        case io.github.md5sha256.realty.api.RealtyBackend.CancelTerminationResult.NotTerminating ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_CANCEL_NOT_TERMINATING,
                                        "region", regionId));
                        case io.github.md5sha256.realty.api.RealtyBackend.CancelTerminationResult.NotAuthorized ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_CANCEL_NOT_AUTHORIZED,
                                        "region", regionId));
                        case io.github.md5sha256.realty.api.RealtyBackend.CancelTerminationResult.UpdateFailed ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_CANCEL_UPDATE_FAILED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.TERMINATE_CANCEL_ERROR,
                            "error", String.valueOf(ex.getMessage())));
                    return null;
                });
    
    }
}
