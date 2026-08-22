package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DateTimeFormatters;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.OfferAcceptEvent;
import io.github.md5sha256.realty.api.event.OfferAcceptedEvent;
import io.github.md5sha256.realty.api.event.OfferPlaceEvent;
import io.github.md5sha256.realty.api.event.OfferPlacedEvent;
import io.github.md5sha256.realty.api.event.OfferPurchaseCompletedEvent;
import io.github.md5sha256.realty.api.event.OfferRejectedEvent;
import io.github.md5sha256.realty.api.event.OfferWithdrawnEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.command.resolver.MemberName;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.database.entity.InboundOfferView;
import io.github.md5sha256.realty.database.entity.OutboundOfferView;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code /realty offer …} family: making, answering and paying for offers on a region. */
@Command({"realty", "rl"})
public final class OfferCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;
    private final PartyService parties;
    private final ExecutorState executorState;

    @Inject
    public OfferCommands(@NotNull RealtyPaperApi api,
                         @NotNull Message messages,
                         @NotNull RealtyEventDispatch events,
                         @NotNull PartyService parties,
                         @NotNull ExecutorState executorState) {
        this.api = api;
        this.messages = messages;
        this.events = events;
        this.parties = parties;
        this.executorState = executorState;
    }

    @Route("offer send <price> [region]")
    @Permission("realty.command.offer.send")
    @Description("Offer to buy a region")
    public void send(@Sender CommandSender rawSender,
                     @Arg("price") double price,
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
        String regionId = region.region().getId();
        if (!this.events.fireSync(new OfferPlaceEvent(region, sender.getUniqueId(), price))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.placeOffer(regionId, region.world().getUID(), sender.getUniqueId(), price)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.OfferResult.Success success -> {
                            sender.sendMessage(this.messages.component(MessageKeys.OFFER_SUCCESS,
                                    "price", CurrencyFormatter.format(price),
                                    "region", regionId));
                            if (success.titleHolderId() != null) {
                                this.events.fireSync(new RealtyNotificationEvent(List.of(success.titleHolderId()),
                                        this.messages.component(MessageKeys.NOTIFICATION_OFFER_PLACED,
                                                "player", sender.getName(),
                                                "price", CurrencyFormatter.format(price),
                                                "region", regionId), region));
                            }
                            this.events.fireSync(new OfferPlacedEvent(region, sender.getUniqueId(),
                                    success.titleHolderId(), price));
                        }
                        case RealtyBackend.OfferResult.NoFreeholdContract ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.OFFER_NO_FREEHOLD_CONTRACT,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.NotAcceptingOffers ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.OFFER_NOT_ACCEPTING,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.IsOwner ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.OFFER_IS_OWNER));
                        case RealtyBackend.OfferResult.AlreadyHasOffer ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.OFFER_ALREADY_HAS_OFFER,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.AuctionExists ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.OFFER_AUCTION_EXISTS,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.InsertFailed ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.OFFER_INSERT_FAILED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    
    }

    @Route("offer inbox")
    @Permission("realty.command.offer.inbox")
    @Description("List offers made on your regions")
    public void inbox(@Sender CommandSender rawSender) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        // A government's inbox belongs to the people who run it: include every government the
        // sender may act for, not just offers addressed to their own UUID.
        inboundForAllParties(sender.getUniqueId()).thenAccept(offers -> {
            try {
                if (offers.isEmpty()) {
                    sender.sendMessage(this.messages.component(MessageKeys.OFFERS_INBOUND_NO_OFFERS));
                    return;
                }

                Component output = this.messages.component(MessageKeys.OFFERS_INBOUND_HEADER);

                for (InboundOfferView offer : offers) {
                    OfflinePlayer offerer = Bukkit.getOfflinePlayer(offer.offererId());
                    String offererName = offerer.getName() != null ? offerer.getName() : offer.offererId().toString();

                    String status;
                    if (offer.accepted()) {
                        double remaining = offer.offerPrice() - offer.currentPayment();
                        status = "Accepted — Paid " + String.format("%.2f", offer.currentPayment())
                                + " / " + String.format("%.2f", offer.offerPrice())
                                + " (remaining: " + String.format("%.2f", remaining) + ")";
                    } else {
                        status = "Pending";
                    }

                    output = output.appendNewline().append(this.messages.component(MessageKeys.OFFERS_INBOUND_ENTRY,
                            "region", offer.worldGuardRegionId(),
                            "player", offererName,
                            "price", String.format("%.2f", offer.offerPrice()),
                            "date", offer.offerTime().format(DateTimeFormatters.DATE_TIME),
                            "status", status));
                }

                sender.sendMessage(output);
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.OFFERS_INBOUND_ERROR,
                        "error", ex.getMessage()));
            }
        });
    
    }

    @Route("offer outbox")
    @Permission("realty.command.offer.outbox")
    @Description("List offers you have made")
    public void outbox(@Sender CommandSender rawSender) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        // Offers are always placed personally, so only the sender's own outbox applies here.
        this.api.listOutboundOffers(sender.getUniqueId()).thenAccept(offers -> {
            try {
                if (offers.isEmpty()) {
                    sender.sendMessage(this.messages.component(MessageKeys.OFFERS_LIST_NO_OFFERS));
                    return;
                }

                Component output = this.messages.component(MessageKeys.OFFERS_LIST_HEADER);

                for (OutboundOfferView offer : offers) {
                    String status;
                    if (offer.accepted()) {
                        double remaining = offer.offerPrice() - offer.currentPayment();
                        status = "Accepted — Paid " + String.format("%.2f", offer.currentPayment())
                                + " / " + String.format("%.2f", offer.offerPrice())
                                + " (remaining: " + String.format("%.2f", remaining) + ")";
                    } else {
                        status = "Pending";
                    }

                    output = output.appendNewline().append(this.messages.component(MessageKeys.OFFERS_LIST_ENTRY,
                            "region", offer.worldGuardRegionId(),
                            "price", String.format("%.2f", offer.offerPrice()),
                            "date", offer.offerTime().format(DateTimeFormatters.DATE_TIME),
                            "status", status));
                }

                sender.sendMessage(output);
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.OFFERS_LIST_ERROR,
                        "error", ex.getMessage()));
            }
        });
    
    }

    @Route("offer accept <player> [region]")
    @Permission("realty.command.offer.accept")
    @Description("Accept an offer on your region")
    public void accept(@Sender CommandSender rawSender,
                       @Arg("player") MemberName player,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        String playerName = player.value();
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYER_NOT_FOUND,
                    "player", playerName));
            return;
        }
        String regionId = region.region().getId();
        if (!this.events.fireSync(new OfferAcceptEvent(region, sender.getUniqueId(), target.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.acceptOffer(regionId, region.world().getUID(), sender.getUniqueId(), target.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.AcceptOfferResult.Success ignored -> {
                            sender.sendMessage(this.messages.component(MessageKeys.ACCEPT_OFFER_SUCCESS,
                                    "player", playerName,
                                    "region", regionId));
                            this.events.fireSync(new RealtyNotificationEvent(List.of(target.getUniqueId()),
                                    this.messages.component(MessageKeys.NOTIFICATION_OFFER_ACCEPTED,
                                            "region", regionId), region));
                            this.events.fireSync(new OfferAcceptedEvent(region, sender.getUniqueId(),
                                    target.getUniqueId()));
                        }
                        case RealtyBackend.AcceptOfferResult.NotSanctioned ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.ACCEPT_OFFER_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.NoOffer ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.ACCEPT_OFFER_NO_OFFER,
                                        "player", playerName,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.AuctionExists ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.ACCEPT_OFFER_AUCTION_EXISTS,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.AlreadyAccepted ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.ACCEPT_OFFER_ALREADY_ACCEPTED,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.InsertFailed ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.ACCEPT_OFFER_INSERT_FAILED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.ACCEPT_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    
    }

    @Route("offer pay <amount> [region]")
    @Permission("realty.command.offer.pay")
    @Description("Pay for an accepted offer")
    public void pay(@Sender CommandSender rawSender,
                    @Arg("amount") double amount,
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
        String regionId = region.region().getId();
        this.api.payOffer(region, sender.getUniqueId(), amount).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.PayOfferResult.Success success ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_SUCCESS,
                                "amount", CurrencyFormatter.format(success.amount()),
                                "region", success.regionId(),
                                "total", CurrencyFormatter.format(success.newTotal()),
                                "remaining", CurrencyFormatter.format(success.remaining())));
                case RealtyPaperApi.PayOfferResult.FullyPaid fullyPaid -> {
                    sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_TRANSFER_SUCCESS,
                            "region", fullyPaid.regionId()));
                    if (fullyPaid.previousTitleHolderId() != null) {
                        this.events.fireSync(new RealtyNotificationEvent(List.of(fullyPaid.previousTitleHolderId()),
                                this.messages.component(MessageKeys.NOTIFICATION_OWNERSHIP_TRANSFERRED,
                                        "player", sender.getName(),
                                        "region", fullyPaid.regionId()), region));
                    }
                    this.events.fireSync(new OfferPurchaseCompletedEvent(region, sender.getUniqueId(),
                            fullyPaid.previousTitleHolderId(), fullyPaid.amount()));
                }
                case RealtyPaperApi.PayOfferResult.NoPaymentRecord noPayment ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_NO_PAYMENT_RECORD,
                                "region", noPayment.regionId()));
                case RealtyPaperApi.PayOfferResult.ExceedsAmountOwed exceeds ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_EXCEEDS_OWED,
                                "amount", CurrencyFormatter.format(exceeds.amount()),
                                "owed", CurrencyFormatter.format(exceeds.amountOwed()),
                                "region", exceeds.regionId()));
                case RealtyPaperApi.PayOfferResult.InsufficientFunds insufficient ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_INSUFFICIENT_FUNDS,
                                "balance", CurrencyFormatter.format(insufficient.balance())));
                case RealtyPaperApi.PayOfferResult.PaymentFailed failed ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_PAYMENT_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.PayOfferResult.TransferFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_TRANSFER_FAILED));
                case RealtyPaperApi.PayOfferResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_OFFER_ERROR,
                                "error", error.message()));
            }
        });
    
    }

    @Route("offer withdraw [region]")
    @Permission("realty.command.offer.withdraw")
    @Description("Withdraw an offer you made")
    public void withdraw(@Sender CommandSender rawSender,
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
        String regionId = region.region().getId();
        this.api.withdrawOffer(regionId, region.world().getUID(), sender.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.WithdrawOfferResult.Success(var titleHolderId) -> {
                            sender.sendMessage(this.messages.component(MessageKeys.WITHDRAW_OFFER_SUCCESS,
                                    "region", regionId));
                            if (titleHolderId != null) {
                                this.events.fireSync(new RealtyNotificationEvent(List.of(titleHolderId),
                                        this.messages.component(MessageKeys.NOTIFICATION_OFFER_WITHDRAWN,
                                                "player", sender.getName(),
                                                "region", regionId), region));
                            }
                            this.events.fireSync(new OfferWithdrawnEvent(region, sender.getUniqueId(), titleHolderId));
                        }
                        case RealtyBackend.WithdrawOfferResult.NoOffer() ->
                                sender.sendMessage(this.messages.component(MessageKeys.WITHDRAW_OFFER_NO_OFFER,
                                        "region", regionId));
                        case RealtyBackend.WithdrawOfferResult.OfferAccepted() ->
                                sender.sendMessage(this.messages.component(MessageKeys.WITHDRAW_OFFER_ACCEPTED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.WITHDRAW_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    
    }

    @Route("offer reject <player> [region]")
    @Permission("realty.command.offer.reject")
    @Description("Reject an offer on your region")
    public void reject(@Sender CommandSender rawSender,
                       @Arg("player") MemberName player,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        String playerName = player.value();
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYER_NOT_FOUND,
                    "player", playerName));
            return;
        }
        String regionId = region.region().getId();
        this.api.rejectOffer(regionId, region.world().getUID(), sender.getUniqueId(), target.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.RejectOfferResult.Success ignored -> {
                            sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_SUCCESS,
                                    "player", playerName,
                                    "region", regionId));
                            this.events.fireSync(new RealtyNotificationEvent(List.of(target.getUniqueId()),
                                    this.messages.component(MessageKeys.NOTIFICATION_OFFER_REJECTED,
                                            "region", regionId), region));
                            this.events.fireSync(new OfferRejectedEvent(region, sender.getUniqueId(),
                                    target.getUniqueId()));
                        }
                        case RealtyBackend.RejectOfferResult.NotSanctioned ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.RejectOfferResult.NoOffer ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_NO_OFFER,
                                        "player", playerName,
                                        "region", regionId));
                        case RealtyBackend.RejectOfferResult.OfferAccepted ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_ACCEPTED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    
    }

    @Route("offer rejectall [region]")
    @Permission("realty.command.offer.reject")
    @Description("Reject every offer on your region")
    public void rejectAll(@Sender CommandSender rawSender,
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
        String regionId = region.region().getId();
        this.api.rejectAllOffers(regionId, region.world().getUID(), sender.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.RejectAllOffersResult.Success success -> {
                            sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_ALL_SUCCESS,
                                    "count", String.valueOf(success.offererIds().size()),
                                    "region", regionId));
                            if (!success.offererIds().isEmpty()) {
                                this.events.fireSync(new RealtyNotificationEvent(List.copyOf(success.offererIds()),
                                        this.messages.component(MessageKeys.NOTIFICATION_OFFER_REJECTED,
                                                "region", regionId), region));
                            }
                            for (UUID offererId : success.offererIds()) {
                                this.events.fireSync(new OfferRejectedEvent(region, sender.getUniqueId(), offererId));
                            }
                        }
                        case RealtyBackend.RejectAllOffersResult.NotSanctioned ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.RejectAllOffersResult.NoFreeholdContract ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_NO_FREEHOLD_CONTRACT,
                                        "region", regionId));
                        case RealtyBackend.RejectAllOffersResult.OfferAccepted ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_ACCEPTED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.REJECT_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    
    }

    @Route("offer toggle <enabled> [region]")
    @Permission("realty.command.offer.toggle")
    @Description("Set whether your region accepts offers")
    public void toggle(@Sender CommandSender rawSender,
                       @Arg("enabled") boolean enabled,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        boolean accepting = enabled;
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        boolean bypass = sender.hasPermission("realty.command.offer.toggle.bypass");
        this.api.toggleOffers(regionId, region.world().getUID(), sender.getUniqueId(), accepting, bypass)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.ToggleOffersResult.Success success ->
                                sender.sendMessage(this.messages.component(MessageKeys.TOGGLE_OFFERS_SUCCESS,
                                        "region", regionId,
                                        "state", success.acceptingOffers() ? "yes" : "no"));
                        case RealtyBackend.ToggleOffersResult.NotSanctioned ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.TOGGLE_OFFERS_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.ToggleOffersResult.NoFreeholdContract ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.TOGGLE_OFFERS_NO_FREEHOLD_CONTRACT,
                                        "region", regionId));
                        case RealtyBackend.ToggleOffersResult.UpdateFailed ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.TOGGLE_OFFERS_UPDATE_FAILED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.TOGGLE_OFFERS_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    
    }

    

    // ── /realty offer send <price> <region> ──

    // ── /realty offer outbox ──

    // ── /realty offer inbox ──

    /**
     * Collects the inbound offers addressed to the sender and to every government they may act for.
     *
     * <p>The party lookup asks Treasury once per registered government, so it runs on the database
     * executor rather than the calling thread.
     */
    private @NotNull CompletableFuture<List<InboundOfferView>> inboundForAllParties(@NotNull UUID actorId) {
        return CompletableFuture
                .supplyAsync(() -> this.parties.partiesFor(actorId), this.executorState.dbExec())
                .thenCompose(partyIds -> {
                    List<CompletableFuture<List<InboundOfferView>>> lookups = partyIds.stream()
                            .map(api::listInboundOffers)
                            .toList();
                    return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> lookups.stream()
                                    .flatMap(lookup -> lookup.join().stream())
                                    .toList());
                });
    }

    // ── /realty offer accept <player> <region> ──

    // ── /realty offer pay <amount> <region> ──

    // ── /realty offer withdraw [region] ──

    // ── /realty offer reject <player> [region] ──

    // ── /realty offer rejectall [region] ──

    // ── /realty offer toggle <yes/no> [region] ──
}
