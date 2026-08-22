package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.LeaseholdModificationStatus;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.LeaseModificationProposedEvent;
import io.github.md5sha256.realty.api.event.LeaseModificationResolvedEvent;
import io.github.md5sha256.realty.api.event.LeaseModifyProposeEvent;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.command.util.LeaseholdChangeSummary;
import io.github.md5sha256.realty.database.entity.LeaseholdModificationView;
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
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /realty modify …} family: proposing a change to a lease's terms and the other
 * party's response.
 *
 * <p>Distinct from {@code /realty set}, which applies a change immediately; a modification
 * has to be accepted before it takes effect.</p>
 */
@Command({"realty", "rl"})
public final class ModifyCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;
    private final PartyService parties;

    @Inject
    public ModifyCommands(@NotNull RealtyPaperApi api,
                          @NotNull Message messages,
                          @NotNull RealtyEventDispatch events,
                          @NotNull PartyService parties) {
        this.api = api;
        this.messages = messages;
        this.events = events;
        this.parties = parties;
    }

    @Route("modify price <price> [region]")
    @Permission("realty.command.modify.price")
    @Description("Propose a new price to the other party")
    public void proposePrice(@Sender CommandSender rawSender,
                              @Arg(value = "price", min = 0.01) double price,
                              @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player sender = playerOrReject(rawSender);
        if (sender == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(sender, namedRegion);
        if (region == null) {
            return;
        }
        propose(sender, region, price, null, null);
    }

    @Route("modify duration <duration> [region]")
    @Permission("realty.command.modify.duration")
    @Description("Propose a new lease term to the other party")
    public void proposeDuration(@Sender CommandSender rawSender,
                                 @Arg("duration") Duration duration,
                                 @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player sender = playerOrReject(rawSender);
        if (sender == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(sender, namedRegion);
        if (region == null) {
            return;
        }
        propose(sender, region, null, duration.toSeconds(), null);
    }

    @Route("modify maxextensions <maxextensions> [region]")
    @Permission("realty.command.modify.maxextensions")
    @Description("Propose a new extension limit to the other party")
    public void proposeMaxExtensions(@Sender CommandSender rawSender,
                                      @Arg(value = "maxextensions", min = 0) int maxextensions,
                                      @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player sender = playerOrReject(rawSender);
        if (sender == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(sender, namedRegion);
        if (region == null) {
            return;
        }
        propose(sender, region, null, null, maxextensions);
    }

    @Route("modify accept [region]")
    @Permission("realty.command.modify.accept")
    @Description("Accept a proposed change")
    public void accept(@Sender CommandSender rawSender,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player sender = playerOrReject(rawSender);
        if (sender == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(sender, namedRegion);
        if (region == null) {
            return;
        }
        resolve(sender, region, ResolveAction.ACCEPT);
    }

    @Route("modify reject [region]")
    @Permission("realty.command.modify.reject")
    @Description("Reject a proposed change")
    public void reject(@Sender CommandSender rawSender,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player sender = playerOrReject(rawSender);
        if (sender == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(sender, namedRegion);
        if (region == null) {
            return;
        }
        resolve(sender, region, ResolveAction.REJECT);
    }

    @Route("modify withdraw [region]")
    @Permission("realty.command.modify.withdraw")
    @Description("Withdraw a change you proposed")
    public void withdraw(@Sender CommandSender rawSender,
                         @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player sender = playerOrReject(rawSender);
        if (sender == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(sender, namedRegion);
        if (region == null) {
            return;
        }
        resolve(sender, region, ResolveAction.WITHDRAW);
    }

    @Route("modify inbox")
    @Permission("realty.command.modify.inbox")
    @Description("List modification proposals sent to you")
    public void inbox(@Sender CommandSender rawSender) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        this.api.listModificationsAwaitingLandlord(sender.getUniqueId()).thenAccept(views -> {
            if (views.isEmpty()) {
                sender.sendMessage(this.messages.component(MessageKeys.MODIFY_INBOX_NONE));
                return;
            }
            Component output = this.messages.component(MessageKeys.MODIFY_INBOX_HEADER);
            for (LeaseholdModificationView view : views) {
                output = output.appendNewline().append(this.messages.component(MessageKeys.MODIFY_INBOX_ENTRY,
                        "region", view.worldGuardRegionId(),
                        "player", resolveName(view.proposerId()),
                        "changes", describeChanges(view)));
            }
            sender.sendMessage(output);
        });
    }

    @Route("modify outbox")
    @Permission("realty.command.modify.outbox")
    @Description("List modification proposals you have sent")
    public void outbox(@Sender CommandSender rawSender) {

        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        this.api.listPendingModificationsByProposer(sender.getUniqueId()).thenAccept(views -> {
            if (views.isEmpty()) {
                sender.sendMessage(this.messages.component(MessageKeys.MODIFY_OUTBOX_NONE));
                return;
            }
            Component output = this.messages.component(MessageKeys.MODIFY_OUTBOX_HEADER);
            for (LeaseholdModificationView view : views) {
                String statusKey = LeaseholdModificationStatus.ACTIVE.equals(view.status())
                        ? MessageKeys.MODIFY_STATUS_ACTIVE : MessageKeys.MODIFY_STATUS_AWAITING;
                output = output.appendNewline().append(this.messages.component(MessageKeys.MODIFY_OUTBOX_ENTRY,
                        "region", view.worldGuardRegionId(),
                        "changes", describeChanges(view),
                        "status", this.messages.component(statusKey)));
            }
            sender.sendMessage(output);
        });
    }

    /**
     * The sender as a player, or {@code null} after telling the console it cannot run this.
     *
     * <p>Each route declares {@code CommandSender} so the console sees Realty's own
     * players-only message rather than the framework's sender-type error.</p>
     */
    private @Nullable Player playerOrReject(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
        return null;
    }

    private @Nullable WorldGuardRegion regionOrReject(@NotNull Player sender,
                                                      @Nullable WorldGuardRegion named) {
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(named, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
        }
        return region;
    }

    private void propose(@NotNull Player sender, @NotNull WorldGuardRegion region,
                         @Nullable Double price, @Nullable Long durationSeconds,
                         @Nullable Integer maxExtensions) {
        if (!this.events.fireSync(new LeaseModifyProposeEvent(region, sender.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        boolean bypass = sender.hasPermission("realty.command.modify.others");
        String regionId = region.region().getId();
        this.api.proposeModification(regionId, region.world().getUID(), sender.getUniqueId(), bypass,
                price, durationSeconds, maxExtensions).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.ProposeModificationResult.Success success -> {
                    String key = success.active()
                            ? MessageKeys.MODIFY_PROPOSE_SUCCESS_LANDLORD
                            : MessageKeys.MODIFY_PROPOSE_SUCCESS_TENANT;
                    sender.sendMessage(this.messages.component(key, "region", regionId));
                    this.events.fireSync(new LeaseModificationProposedEvent(region, success.proposerRole(),
                            sender.getUniqueId(), success.landlordId(), success.tenantId(), success.active()));
                }
                case RealtyBackend.ProposeModificationResult.NoLeaseholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_NO_LEASEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyBackend.ProposeModificationResult.NotOccupied ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_NOT_OCCUPIED,
                                "region", regionId));
                case RealtyBackend.ProposeModificationResult.Terminating ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_TERMINATING,
                                "region", regionId));
                case RealtyBackend.ProposeModificationResult.NotAuthorized ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_NOT_AUTHORIZED,
                                "region", regionId));
                case RealtyBackend.ProposeModificationResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_UPDATE_FAILED,
                                "region", regionId));
            }
        }).exceptionally(ex -> {
            sender.sendMessage(this.messages.component(MessageKeys.MODIFY_ERROR,
                    "error", String.valueOf(ex.getMessage())));
            return null;
        });
    }

    private void resolve(@NotNull Player sender, @NotNull WorldGuardRegion region,
                         @NotNull ResolveAction action) {
        boolean bypass = sender.hasPermission("realty.command.modify.others");
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        UUID actorId = sender.getUniqueId();
        var future = switch (action) {
            case ACCEPT -> this.api.acceptModification(regionId, worldId, actorId, bypass);
            case REJECT -> this.api.rejectModification(regionId, worldId, actorId, bypass);
            case WITHDRAW -> this.api.withdrawModification(regionId, worldId, actorId, bypass);
        };
        future.thenAccept(result -> {
            switch (result) {
                case RealtyBackend.ResolveModificationResult.Success success -> {
                    sender.sendMessage(this.messages.component(action.successKey,
                            "region", regionId));
                    this.events.fireSync(new LeaseModificationResolvedEvent(region, action.resolution,
                            success.proposerRole(), success.landlordId(), success.tenantId()));
                }
                case RealtyBackend.ResolveModificationResult.NoLeaseholdContract ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_NO_LEASEHOLD_CONTRACT,
                                "region", regionId));
                case RealtyBackend.ResolveModificationResult.NoPendingProposal ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_NO_PENDING_PROPOSAL,
                                "region", regionId));
                case RealtyBackend.ResolveModificationResult.NotTenantProposal ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_NOT_TENANT_PROPOSAL,
                                "region", regionId));
                case RealtyBackend.ResolveModificationResult.NotAuthorized ignored ->
                        sender.sendMessage(this.messages.component(
                                action == ResolveAction.WITHDRAW
                                        ? MessageKeys.MODIFY_NOT_PROPOSER
                                        : MessageKeys.MODIFY_NOT_LANDLORD,
                                "region", regionId));
                case RealtyBackend.ResolveModificationResult.UpdateFailed ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.MODIFY_UPDATE_FAILED,
                                "region", regionId));
            }
        }).exceptionally(ex -> {
            sender.sendMessage(this.messages.component(MessageKeys.MODIFY_ERROR,
                    "error", String.valueOf(ex.getMessage())));
            return null;
        });
    }

    

    /** Renders the non-null proposed terms as a localized summary (formatting lives in this.messages.properties). */
    private @NotNull Component describeChanges(@NotNull LeaseholdModificationView view) {
        return LeaseholdChangeSummary.render(messages,
                view.newPrice(), view.newDurationSeconds(), view.newMaxExtensions());
    }

    private @NotNull String resolveName(@NotNull UUID uuid) {
        return PartyNames.resolve(this.parties, uuid);
    }

    /** The three ways to resolve a pending proposal, each carrying its success message and resolution name. */
    private enum ResolveAction {
        ACCEPT(MessageKeys.MODIFY_ACCEPT_SUCCESS, "ACCEPTED"),
        REJECT(MessageKeys.MODIFY_REJECT_SUCCESS, "REJECTED"),
        WITHDRAW(MessageKeys.MODIFY_WITHDRAW_SUCCESS, "WITHDRAWN");

        private final String successKey;
        private final String resolution;

        ResolveAction(String successKey, String resolution) {
            this.successKey = successKey;
            this.resolution = resolution;
        }
    }
}
