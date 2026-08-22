package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.minecraftcitiesnetwork.pluginInfrastructure.util.DateFormatter;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DurationFormatter;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.AuctionBidEvent;
import io.github.md5sha256.realty.api.event.AuctionBidPlacedEvent;
import io.github.md5sha256.realty.api.event.AuctionCancelledEvent;
import io.github.md5sha256.realty.api.event.AuctionCreateEvent;
import io.github.md5sha256.realty.api.event.AuctionCreatedEvent;
import io.github.md5sha256.realty.api.event.AuctionWonPurchaseEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.database.entity.FreeholdContractAuctionEntity;
import io.github.md5sha256.realty.database.entity.FreeholdContractBid;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyNames;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.settings.Settings;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code /realty auction …} family: creating, bidding on and settling auctions. */
@Command({"realty", "rl"})
public final class AuctionCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final AtomicReference<Settings> settings;
    private final Message messages;
    private final RealtyEventDispatch events;
    private final PartyService parties;

    @Inject
    public AuctionCommands(@NotNull RealtyPaperApi api,
                           @NotNull AtomicReference<Settings> settings,
                           @NotNull Message messages,
                           @NotNull RealtyEventDispatch events,
                           @NotNull PartyService parties) {
        this.api = api;
        this.settings = settings;
        this.messages = messages;
        this.events = events;
        this.parties = parties;
    }

    @Route("auction info [region]")
    @Permission("realty.command.auction.info")
    @Description("Show an auction's details")
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

        this.api.getRegionInfo(regionId, worldId).thenAccept(regionInfo -> {
            try {
                FreeholdContractAuctionEntity auction = regionInfo.auction();
                if (auction == null) {
                    sender.sendMessage(this.messages.component(MessageKeys.AUCTION_INFO_NO_AUCTION,
                            "region", regionId));
                    return;
                }
                TextComponent.Builder textBuilder = Component.text();
                textBuilder.append(this.messages.component(MessageKeys.AUCTION_INFO_HEADER,
                        "region", regionId));
                FreeholdContractBid highestBid = regionInfo.highestBid();
                String highestBidAmount = highestBid != null ? CurrencyFormatter.format(highestBid.bidAmount()) : "N/A";
                String highestBidPlayer = highestBid != null ? resolveName(highestBid.bidderId()) : "N/A";
                LocalDateTime lastActivity = highestBid != null ? highestBid.bidTime() : auction.startDate();
                LocalDateTime biddingEndDate = lastActivity.plusSeconds(auction.biddingDurationSeconds());

                textBuilder.appendNewline()
                        .append(this.messages.component(MessageKeys.AUCTION_INFO_DETAILS,
                                "auctioneer", resolveName(auction.auctioneerId()),
                                "start_date", DateFormatter.format(this.settings.get().dateFormat(), auction.startDate()),
                                "duration", DurationFormatter.format(Duration.ofSeconds(auction.biddingDurationSeconds())),
                                "bidding_end_date", DateFormatter.format(this.settings.get().dateFormat(), biddingEndDate),
                                "deadline", DateFormatter.format(this.settings.get().dateFormat(), auction.paymentDeadline()),
                                "min_bid", CurrencyFormatter.format(auction.minBid()),
                                "min_step", CurrencyFormatter.format(auction.minStep()),
                                "highest_bid_amount", highestBidAmount,
                                "highest_bid_player", highestBidPlayer));
                sender.sendMessage(textBuilder.build());
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.AUCTION_INFO_ERROR,
                        "error", ex.getMessage()));
            }
        });
    
    }

    @Route("auction <bidDuration> <paymentDuration> <minBid> <minBidStep> [region]")
    @Permission("realty.command.auction")
    @Description("Start an auction for a region")
    public void create(@Sender CommandSender sender,
                       @Arg("bidDuration") Duration bidDuration,
                       @Arg("paymentDuration") Duration paymentDuration,
                       @Arg("minBid") double minBid,
                       @Arg("minBidStep") double minBidStep,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, player);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        if (!this.events.fireSync(new AuctionCreateEvent(region, player.getUniqueId(), minBid, minBidStep,
                bidDuration.toSeconds(), paymentDuration.toSeconds()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.createAuction(
                regionId,
                region.world().getUID(),
                player.getUniqueId(),
                bidDuration.toSeconds(),
                paymentDuration.toSeconds(),
                minBid,
                minBidStep
        ).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.CreateAuctionResult.Success ignored -> {
                        sender.sendMessage(this.messages.component(MessageKeys.AUCTION_SUCCESS,
                                "region", regionId));
                        this.events.fireSync(new AuctionCreatedEvent(region, player.getUniqueId(),
                                minBid, minBidStep));
                }
                case RealtyBackend.CreateAuctionResult.NotSanctioned ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.AUCTION_NOT_SANCTIONED,
                                "region", regionId));
                case RealtyBackend.CreateAuctionResult.NoFreeholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.AUCTION_NO_FREEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyBackend.CreateAuctionResult.OffersExist ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.AUCTION_OFFERS_EXIST,
                                "region", regionId));
            }
        }).exceptionally(ex -> {
            sender.sendMessage(this.messages.component(MessageKeys.AUCTION_ERROR,
                    "error", ex.getMessage()));
            return null;
        });
    
    }

    @Route("auction cancel [region]")
    @Permission("realty.command.auction.cancel")
    @Description("Cancel an auction")
    public void cancel(@Sender CommandSender sender,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        this.api.cancelAuction(regionId, region.world().getUID()).thenAccept(result -> {
            if (result.deleted() == 0) {
                sender.sendMessage(this.messages.component(MessageKeys.CANCEL_AUCTION_NO_AUCTION));
                return;
            }
            sender.sendMessage(this.messages.component(MessageKeys.CANCEL_AUCTION_SUCCESS,
                    "region", regionId));
            for (UUID bidderId : result.bidderIds()) {
                this.events.fireSync(new RealtyNotificationEvent(List.of(bidderId),
                        this.messages.component(MessageKeys.NOTIFICATION_AUCTION_CANCELLED,
                                "region", regionId), region));
            }
            if (sender instanceof Player canceller) {
                this.events.fireSync(new AuctionCancelledEvent(region, canceller.getUniqueId()));
            }
        }).exceptionally(ex -> {
            sender.sendMessage(this.messages.component(MessageKeys.CANCEL_AUCTION_ERROR,
                    "error", ex.getMessage()));
            return null;
        });
    
    }

    @Route("auction bid <bid> [region]")
    @Permission("realty.command.auction.bid")
    @Description("Place a bid on an auction")
    public void bid(@Sender CommandSender rawSender,
                    @Arg("bid") double bid,
                    @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        double bidAmount = bid;
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        if (!this.events.fireSync(new AuctionBidEvent(region, sender.getUniqueId(), bidAmount))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.performBid(regionId, region.world().getUID(), sender.getUniqueId(), bidAmount)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.BidResult.Success success -> {
                            sender.sendMessage(this.messages.component(MessageKeys.BID_SUCCESS,
                                    "amount", CurrencyFormatter.format(bidAmount),
                                    "region", regionId));
                            if (success.previousBidderId() != null) {
                                this.events.fireSync(new RealtyNotificationEvent(List.of(success.previousBidderId()),
                                        this.messages.component(MessageKeys.NOTIFICATION_OUTBID,
                                                "region", regionId,
                                                "amount", CurrencyFormatter.format(bidAmount)), region));
                            }
                            this.events.fireSync(new AuctionBidPlacedEvent(region, sender.getUniqueId(), bidAmount));
                        }
                        case RealtyBackend.BidResult.NoAuction ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.BID_NO_AUCTION));
                        case RealtyBackend.BidResult.IsOwner ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.BID_IS_OWNER));
                        case RealtyBackend.BidResult.BidTooLowMinimum r ->
                                sender.sendMessage(this.messages.component(MessageKeys.BID_TOO_LOW_MINIMUM,
                                        "amount", CurrencyFormatter.format(r.minBid())));
                        case RealtyBackend.BidResult.BidTooLowCurrent r ->
                                sender.sendMessage(this.messages.component(MessageKeys.BID_TOO_LOW_CURRENT,
                                        "amount", CurrencyFormatter.format(r.currentHighest())));
                        case RealtyBackend.BidResult.AlreadyHighestBidder ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.BID_ALREADY_HIGHEST));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.BID_ERROR,
                            "error", ex.getMessage()));
                    return null;
                });
    
    }

    @Route("auction paybid <amount> [region]")
    @Permission("realty.command.auction.paybid")
    @Description("Pay for an auction you won")
    public void payBid(@Sender CommandSender rawSender,
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
        this.api.payBid(region, sender.getUniqueId(), amount).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.PayBidResult.Success success ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_SUCCESS,
                                "amount", CurrencyFormatter.format(success.amount()),
                                "region", success.regionId(),
                                "total", CurrencyFormatter.format(success.newTotal()),
                                "remaining", CurrencyFormatter.format(success.remaining())));
                case RealtyPaperApi.PayBidResult.FullyPaid fullyPaid -> {
                    sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_TRANSFER_SUCCESS,
                            "region", fullyPaid.regionId()));
                    if (fullyPaid.previousTitleHolderId() != null) {
                        this.events.fireSync(new RealtyNotificationEvent(List.of(fullyPaid.previousTitleHolderId()),
                                this.messages.component(MessageKeys.NOTIFICATION_OWNERSHIP_TRANSFERRED,
                                        "player", sender.getName(),
                                        "region", fullyPaid.regionId()), region));
                    }
                    this.events.fireSync(new AuctionWonPurchaseEvent(region, sender.getUniqueId(),
                            fullyPaid.previousTitleHolderId(), fullyPaid.amount()));
                }
                case RealtyPaperApi.PayBidResult.NoPaymentRecord noPayment ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_NO_PAYMENT_RECORD,
                                "region", noPayment.regionId()));
                case RealtyPaperApi.PayBidResult.PaymentExpired expired ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_PAYMENT_EXPIRED,
                                "region", expired.regionId()));
                case RealtyPaperApi.PayBidResult.ExceedsAmountOwed exceeds ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_EXCEEDS_OWED,
                                "amount", CurrencyFormatter.format(exceeds.amount()),
                                "owed", CurrencyFormatter.format(exceeds.amountOwed()),
                                "region", exceeds.regionId()));
                case RealtyPaperApi.PayBidResult.InsufficientFunds insufficient ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_INSUFFICIENT_FUNDS,
                                "balance", CurrencyFormatter.format(insufficient.balance())));
                case RealtyPaperApi.PayBidResult.PaymentFailed failed ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_PAYMENT_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.PayBidResult.TransferFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_TRANSFER_FAILED));
                case RealtyPaperApi.PayBidResult.Error error ->
                        sender.sendMessage(this.messages.component(MessageKeys.PAY_BID_ERROR,
                                "error", error.message()));
            }
        });
    
    }

    

    // ── /realty auction info [region] ──

    private @NotNull String resolveName(@NotNull UUID uuid) {
        return PartyNames.resolve(parties, uuid);
    }

    // ── /realty auction <bidDuration> <paymentDuration> <minBid> <minBidStep> <region> ──

    // ── /realty auction cancel [region] ──

    // ── /realty auction bid <amount> <region> ──

    // ── /realty auction paybid <amount> <region> ──
}
