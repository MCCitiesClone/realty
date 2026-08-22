package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DateTimeFormatters;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.command.util.ParseBounds;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.OfferAcceptEvent;
import io.github.md5sha256.realty.api.event.OfferAcceptedEvent;
import io.github.md5sha256.realty.api.event.OfferPlaceEvent;
import io.github.md5sha256.realty.api.event.OfferPlacedEvent;
import io.github.md5sha256.realty.api.event.OfferPurchaseCompletedEvent;
import io.github.md5sha256.realty.api.event.OfferRejectedEvent;
import io.github.md5sha256.realty.api.event.OfferWithdrawnEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.command.util.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.database.entity.InboundOfferView;
import io.github.md5sha256.realty.database.entity.OutboundOfferView;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.party.PartyService;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.paper.util.sender.Source;
import org.incendo.cloud.parser.standard.BooleanParser;
import org.incendo.cloud.parser.standard.DoubleParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Groups all offer-related subcommands under {@code /realty offer}.
 *
 * <ul>
 *   <li>{@code /realty offer send <price> <region>}</li>
 *   <li>{@code /realty offer inbox}</li>
 *   <li>{@code /realty offer outbox}</li>
 *   <li>{@code /realty offer accept <player> <region>}</li>
 *   <li>{@code /realty offer pay <amount> <region>}</li>
 *   <li>{@code /realty offer withdraw <region>}</li>
 * </ul>
 */
public record OfferCommandGroup(
        @NotNull RealtyPaperApi api,
        @NotNull Message messages,
        @NotNull RealtyEventDispatch events,
        @NotNull PartyService parties,
        @NotNull ExecutorState executorState
) implements CustomCommandBean {

    @Override
    public @NotNull List<Command<? extends Source>> commands(@NotNull Command.Builder<Source> builder) {
        var base = builder.literal("offer");
        return List.of(
                base.literal("send")
                        .permission("realty.command.offer.send")
                        .required("price", DoubleParser.doubleParser(ParseBounds.MIN_STRICTLY_POSITIVE,
                                Double.MAX_VALUE))
                        .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                        .handler(this::executeSend)
                        .build(),
                base.literal("inbox")
                        .permission("realty.command.offer.inbox")
                        .handler(this::executeInbox)
                        .build(),
                base.literal("outbox")
                        .permission("realty.command.offer.outbox")
                        .handler(this::executeOutbox)
                        .build(),
                base.literal("accept")
                        .permission("realty.command.offer.accept")
                        .required("player", StringParser.stringParser(), playerSuggestions())
                        .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                        .handler(this::executeAccept)
                        .build(),
                base.literal("pay")
                        .permission("realty.command.offer.pay")
                        .required("amount", DoubleParser.doubleParser(0, Double.MAX_VALUE))
                        .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                        .handler(this::executePay)
                        .build(),
                base.literal("withdraw")
                        .permission("realty.command.offer.withdraw")
                        .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                        .handler(this::executeWithdraw)
                        .build(),
                base.literal("reject")
                        .permission("realty.command.offer.reject")
                        .required("player", StringParser.stringParser(), playerSuggestions())
                        .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                        .handler(this::executeReject)
                        .build(),
                base.literal("rejectall")
                        .permission("realty.command.offer.reject")
                        .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                        .handler(this::executeRejectAll)
                        .build(),
                base.literal("toggle")
                        .permission("realty.command.offer.toggle")
                        .required("enabled", BooleanParser.booleanParser())
                        .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                        .handler(this::executeToggle)
                        .build()
        );
    }

    private static @NotNull SuggestionProvider<Source> playerSuggestions() {
        return (ctx, input) -> CompletableFuture.completedFuture(
                Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .map(Suggestion::suggestion)
                        .toList()
        );
    }

    // ── /realty offer send <price> <region> ──

    private void executeSend(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        double price = ctx.get("price");
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        if (!events.fireSync(new OfferPlaceEvent(region, sender.getUniqueId(), price))) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        api.placeOffer(regionId, region.world().getUID(), sender.getUniqueId(), price)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.OfferResult.Success success -> {
                            sender.sendMessage(messages.component(MessageKeys.OFFER_SUCCESS,
                                    "price", CurrencyFormatter.format(price),
                                    "region", regionId));
                            if (success.titleHolderId() != null) {
                                events.fireSync(new RealtyNotificationEvent(List.of(success.titleHolderId()),
                                        messages.component(MessageKeys.NOTIFICATION_OFFER_PLACED,
                                                "player", sender.getName(),
                                                "price", CurrencyFormatter.format(price),
                                                "region", regionId), region));
                            }
                            events.fireSync(new OfferPlacedEvent(region, sender.getUniqueId(),
                                    success.titleHolderId(), price));
                        }
                        case RealtyBackend.OfferResult.NoFreeholdContract ignored ->
                                sender.sendMessage(messages.component(MessageKeys.OFFER_NO_FREEHOLD_CONTRACT,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.NotAcceptingOffers ignored ->
                                sender.sendMessage(messages.component(MessageKeys.OFFER_NOT_ACCEPTING,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.IsOwner ignored ->
                                sender.sendMessage(messages.component(MessageKeys.OFFER_IS_OWNER));
                        case RealtyBackend.OfferResult.AlreadyHasOffer ignored ->
                                sender.sendMessage(messages.component(MessageKeys.OFFER_ALREADY_HAS_OFFER,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.AuctionExists ignored ->
                                sender.sendMessage(messages.component(MessageKeys.OFFER_AUCTION_EXISTS,
                                        "region", regionId));
                        case RealtyBackend.OfferResult.InsertFailed ignored ->
                                sender.sendMessage(messages.component(MessageKeys.OFFER_INSERT_FAILED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(messages.component(MessageKeys.OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    }

    // ── /realty offer outbox ──

    private void executeOutbox(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        // Offers are always placed personally, so only the sender's own outbox applies here.
        api.listOutboundOffers(sender.getUniqueId()).thenAccept(offers -> {
            try {
                if (offers.isEmpty()) {
                    sender.sendMessage(messages.component(MessageKeys.OFFERS_LIST_NO_OFFERS));
                    return;
                }

                Component output = messages.component(MessageKeys.OFFERS_LIST_HEADER);

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

                    output = output.appendNewline().append(messages.component(MessageKeys.OFFERS_LIST_ENTRY,
                            "region", offer.worldGuardRegionId(),
                            "price", String.format("%.2f", offer.offerPrice()),
                            "date", offer.offerTime().format(DateTimeFormatters.DATE_TIME),
                            "status", status));
                }

                sender.sendMessage(output);
            } catch (Exception ex) {
                sender.sendMessage(messages.component(MessageKeys.OFFERS_LIST_ERROR,
                        "error", ex.getMessage()));
            }
        });
    }

    // ── /realty offer inbox ──

    private void executeInbox(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        // A government's inbox belongs to the people who run it: include every government the
        // sender may act for, not just offers addressed to their own UUID.
        inboundForAllParties(sender.getUniqueId()).thenAccept(offers -> {
            try {
                if (offers.isEmpty()) {
                    sender.sendMessage(messages.component(MessageKeys.OFFERS_INBOUND_NO_OFFERS));
                    return;
                }

                Component output = messages.component(MessageKeys.OFFERS_INBOUND_HEADER);

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

                    output = output.appendNewline().append(messages.component(MessageKeys.OFFERS_INBOUND_ENTRY,
                            "region", offer.worldGuardRegionId(),
                            "player", offererName,
                            "price", String.format("%.2f", offer.offerPrice()),
                            "date", offer.offerTime().format(DateTimeFormatters.DATE_TIME),
                            "status", status));
                }

                sender.sendMessage(output);
            } catch (Exception ex) {
                sender.sendMessage(messages.component(MessageKeys.OFFERS_INBOUND_ERROR,
                        "error", ex.getMessage()));
            }
        });
    }

    /**
     * Collects the inbound offers addressed to the sender and to every government they may act for.
     *
     * <p>The party lookup asks Treasury once per registered government, so it runs on the database
     * executor rather than the calling thread.
     */
    private @NotNull CompletableFuture<List<InboundOfferView>> inboundForAllParties(@NotNull UUID actorId) {
        return CompletableFuture
                .supplyAsync(() -> parties.partiesFor(actorId), executorState.dbExec())
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

    private void executeAccept(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        String playerName = ctx.get("player");
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_PLAYER_NOT_FOUND,
                    "player", playerName));
            return;
        }
        String regionId = region.region().getId();
        if (!events.fireSync(new OfferAcceptEvent(region, sender.getUniqueId(), target.getUniqueId()))) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        api.acceptOffer(regionId, region.world().getUID(), sender.getUniqueId(), target.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.AcceptOfferResult.Success ignored -> {
                            sender.sendMessage(messages.component(MessageKeys.ACCEPT_OFFER_SUCCESS,
                                    "player", playerName,
                                    "region", regionId));
                            events.fireSync(new RealtyNotificationEvent(List.of(target.getUniqueId()),
                                    messages.component(MessageKeys.NOTIFICATION_OFFER_ACCEPTED,
                                            "region", regionId), region));
                            events.fireSync(new OfferAcceptedEvent(region, sender.getUniqueId(),
                                    target.getUniqueId()));
                        }
                        case RealtyBackend.AcceptOfferResult.NotSanctioned ignored ->
                                sender.sendMessage(messages.component(MessageKeys.ACCEPT_OFFER_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.NoOffer ignored ->
                                sender.sendMessage(messages.component(MessageKeys.ACCEPT_OFFER_NO_OFFER,
                                        "player", playerName,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.AuctionExists ignored ->
                                sender.sendMessage(messages.component(MessageKeys.ACCEPT_OFFER_AUCTION_EXISTS,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.AlreadyAccepted ignored ->
                                sender.sendMessage(messages.component(MessageKeys.ACCEPT_OFFER_ALREADY_ACCEPTED,
                                        "region", regionId));
                        case RealtyBackend.AcceptOfferResult.InsertFailed ignored ->
                                sender.sendMessage(messages.component(MessageKeys.ACCEPT_OFFER_INSERT_FAILED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(messages.component(MessageKeys.ACCEPT_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    }

    // ── /realty offer pay <amount> <region> ──

    private void executePay(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        double amount = ctx.get("amount");
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        api.payOffer(region, sender.getUniqueId(), amount).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.PayOfferResult.Success success ->
                        sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_SUCCESS,
                                "amount", CurrencyFormatter.format(success.amount()),
                                "region", success.regionId(),
                                "total", CurrencyFormatter.format(success.newTotal()),
                                "remaining", CurrencyFormatter.format(success.remaining())));
                case RealtyPaperApi.PayOfferResult.FullyPaid fullyPaid -> {
                    sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_TRANSFER_SUCCESS,
                            "region", fullyPaid.regionId()));
                    if (fullyPaid.previousTitleHolderId() != null) {
                        events.fireSync(new RealtyNotificationEvent(List.of(fullyPaid.previousTitleHolderId()),
                                messages.component(MessageKeys.NOTIFICATION_OWNERSHIP_TRANSFERRED,
                                        "player", sender.getName(),
                                        "region", fullyPaid.regionId()), region));
                    }
                    events.fireSync(new OfferPurchaseCompletedEvent(region, sender.getUniqueId(),
                            fullyPaid.previousTitleHolderId(), fullyPaid.amount()));
                }
                case RealtyPaperApi.PayOfferResult.NoPaymentRecord noPayment ->
                        sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_NO_PAYMENT_RECORD,
                                "region", noPayment.regionId()));
                case RealtyPaperApi.PayOfferResult.ExceedsAmountOwed exceeds ->
                        sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_EXCEEDS_OWED,
                                "amount", CurrencyFormatter.format(exceeds.amount()),
                                "owed", CurrencyFormatter.format(exceeds.amountOwed()),
                                "region", exceeds.regionId()));
                case RealtyPaperApi.PayOfferResult.InsufficientFunds insufficient ->
                        sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_INSUFFICIENT_FUNDS,
                                "balance", CurrencyFormatter.format(insufficient.balance())));
                case RealtyPaperApi.PayOfferResult.PaymentFailed failed ->
                        sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_PAYMENT_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.PayOfferResult.TransferFailed ignored ->
                        sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_TRANSFER_FAILED));
                case RealtyPaperApi.PayOfferResult.Error error ->
                        sender.sendMessage(messages.component(MessageKeys.PAY_OFFER_ERROR,
                                "error", error.message()));
            }
        });
    }

    // ── /realty offer withdraw [region] ──

    private void executeWithdraw(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        api.withdrawOffer(regionId, region.world().getUID(), sender.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.WithdrawOfferResult.Success(var titleHolderId) -> {
                            sender.sendMessage(messages.component(MessageKeys.WITHDRAW_OFFER_SUCCESS,
                                    "region", regionId));
                            if (titleHolderId != null) {
                                events.fireSync(new RealtyNotificationEvent(List.of(titleHolderId),
                                        messages.component(MessageKeys.NOTIFICATION_OFFER_WITHDRAWN,
                                                "player", sender.getName(),
                                                "region", regionId), region));
                            }
                            events.fireSync(new OfferWithdrawnEvent(region, sender.getUniqueId(), titleHolderId));
                        }
                        case RealtyBackend.WithdrawOfferResult.NoOffer() ->
                                sender.sendMessage(messages.component(MessageKeys.WITHDRAW_OFFER_NO_OFFER,
                                        "region", regionId));
                        case RealtyBackend.WithdrawOfferResult.OfferAccepted() ->
                                sender.sendMessage(messages.component(MessageKeys.WITHDRAW_OFFER_ACCEPTED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(messages.component(MessageKeys.WITHDRAW_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    }

    // ── /realty offer reject <player> [region] ──

    private void executeReject(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        String playerName = ctx.get("player");
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_PLAYER_NOT_FOUND,
                    "player", playerName));
            return;
        }
        String regionId = region.region().getId();
        api.rejectOffer(regionId, region.world().getUID(), sender.getUniqueId(), target.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.RejectOfferResult.Success ignored -> {
                            sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_SUCCESS,
                                    "player", playerName,
                                    "region", regionId));
                            events.fireSync(new RealtyNotificationEvent(List.of(target.getUniqueId()),
                                    messages.component(MessageKeys.NOTIFICATION_OFFER_REJECTED,
                                            "region", regionId), region));
                            events.fireSync(new OfferRejectedEvent(region, sender.getUniqueId(),
                                    target.getUniqueId()));
                        }
                        case RealtyBackend.RejectOfferResult.NotSanctioned ignored ->
                                sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.RejectOfferResult.NoOffer ignored ->
                                sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_NO_OFFER,
                                        "player", playerName,
                                        "region", regionId));
                        case RealtyBackend.RejectOfferResult.OfferAccepted ignored ->
                                sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_ACCEPTED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    }

    // ── /realty offer rejectall [region] ──

    private void executeRejectAll(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        api.rejectAllOffers(regionId, region.world().getUID(), sender.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.RejectAllOffersResult.Success success -> {
                            sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_ALL_SUCCESS,
                                    "count", String.valueOf(success.offererIds().size()),
                                    "region", regionId));
                            if (!success.offererIds().isEmpty()) {
                                events.fireSync(new RealtyNotificationEvent(List.copyOf(success.offererIds()),
                                        messages.component(MessageKeys.NOTIFICATION_OFFER_REJECTED,
                                                "region", regionId), region));
                            }
                            for (UUID offererId : success.offererIds()) {
                                events.fireSync(new OfferRejectedEvent(region, sender.getUniqueId(), offererId));
                            }
                        }
                        case RealtyBackend.RejectAllOffersResult.NotSanctioned ignored ->
                                sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.RejectAllOffersResult.NoFreeholdContract ignored ->
                                sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_NO_FREEHOLD_CONTRACT,
                                        "region", regionId));
                        case RealtyBackend.RejectAllOffersResult.OfferAccepted ignored ->
                                sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_ACCEPTED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(messages.component(MessageKeys.REJECT_OFFER_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    }

    // ── /realty offer toggle <yes/no> [region] ──

    private void executeToggle(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        boolean accepting = ctx.get("enabled");
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        boolean bypass = sender.hasPermission("realty.command.offer.toggle.bypass");
        api.toggleOffers(regionId, region.world().getUID(), sender.getUniqueId(), accepting, bypass)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.ToggleOffersResult.Success success ->
                                sender.sendMessage(messages.component(MessageKeys.TOGGLE_OFFERS_SUCCESS,
                                        "region", regionId,
                                        "state", success.acceptingOffers() ? "yes" : "no"));
                        case RealtyBackend.ToggleOffersResult.NotSanctioned ignored ->
                                sender.sendMessage(messages.component(MessageKeys.TOGGLE_OFFERS_NOT_SANCTIONED,
                                        "region", regionId));
                        case RealtyBackend.ToggleOffersResult.NoFreeholdContract ignored ->
                                sender.sendMessage(messages.component(MessageKeys.TOGGLE_OFFERS_NO_FREEHOLD_CONTRACT,
                                        "region", regionId));
                        case RealtyBackend.ToggleOffersResult.UpdateFailed ignored ->
                                sender.sendMessage(messages.component(MessageKeys.TOGGLE_OFFERS_UPDATE_FAILED,
                                        "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(messages.component(MessageKeys.TOGGLE_OFFERS_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    }

}
