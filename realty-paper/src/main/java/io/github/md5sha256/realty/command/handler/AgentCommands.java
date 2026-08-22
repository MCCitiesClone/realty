package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.AgentInviteAcceptedEvent;
import io.github.md5sha256.realty.api.event.AgentInviteEvent;
import io.github.md5sha256.realty.api.event.AgentInviteRejectedEvent;
import io.github.md5sha256.realty.api.event.AgentInviteWithdrawnEvent;
import io.github.md5sha256.realty.api.event.AgentInvitedEvent;
import io.github.md5sha256.realty.api.event.AgentRemovedEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.command.resolver.PlayerAuthority;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The {@code /realty agent …} family: inviting an agent to act on a freehold, the invitee's
 * response, and removal.
 *
 * <p>The subject is a {@link PlayerAuthority} rather than the party type the title-holder commands
 * accept: an agent acts on behalf of a person, so a government is not a valid invitee, and the
 * resolver enforces that before the handler runs.</p>
 */
@Command({"realty", "rl"})
public final class AgentCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public AgentCommands(@NotNull RealtyPaperApi api,
                         @NotNull Message messages,
                         @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("agent invite <player> [region]")
    @Permission("realty.command.agent.invite")
    @Description("Invite a player to act as an agent for a freehold")
    public void invite(@Sender CommandSender rawSender,
                       @Arg("player") PlayerAuthority invitee,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player player = playerOrReject(rawSender);
        if (player == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(player, namedRegion);
        if (region == null) {
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        UUID inviteeId = invitee.uuid();
        String inviteeName = invitee.name();
        if (!region.region().getOwners().contains(player.getUniqueId())) {
            player.sendMessage(this.messages.component(
                    MessageKeys.AGENT_INVITE_NOT_TITLEHOLDER, "region", regionId));
            return;
        }
        if (!this.events.fireSync(new AgentInviteEvent(region, player.getUniqueId(), inviteeId))) {
            player.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.inviteAgent(regionId, worldId, player.getUniqueId(), inviteeId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.InviteAgentResult.Success() -> {
                    player.sendMessage(this.messages.component(MessageKeys.AGENT_INVITE_SUCCESS,
                            "player", inviteeName, "region", regionId));
                    this.events.fireSync(new RealtyNotificationEvent(List.of(inviteeId),
                            this.messages.component(MessageKeys.NOTIFICATION_AGENT_INVITED,
                                    "player", player.getName(), "region", regionId), region));
                    this.events.fireSync(new AgentInvitedEvent(region, player.getUniqueId(), inviteeId));
                }
                case RealtyBackend.InviteAgentResult.NoFreeholdContract() ->
                        player.sendMessage(this.messages.component(
                                MessageKeys.AGENT_INVITE_NO_FREEHOLD, "region", regionId));
                case RealtyBackend.InviteAgentResult.IsTitleHolder() ->
                        player.sendMessage(this.messages.component(MessageKeys.AGENT_INVITE_IS_TITLEHOLDER,
                                "player", inviteeName, "region", regionId));
                case RealtyBackend.InviteAgentResult.IsAuthority() ->
                        player.sendMessage(this.messages.component(MessageKeys.AGENT_INVITE_IS_AUTHORITY,
                                "player", inviteeName, "region", regionId));
                case RealtyBackend.InviteAgentResult.AlreadyAgent() ->
                        player.sendMessage(this.messages.component(MessageKeys.AGENT_INVITE_ALREADY_AGENT,
                                "player", inviteeName, "region", regionId));
                case RealtyBackend.InviteAgentResult.AlreadyInvited() ->
                        player.sendMessage(this.messages.component(MessageKeys.AGENT_INVITE_ALREADY_INVITED,
                                "player", inviteeName, "region", regionId));
                case RealtyBackend.InviteAgentResult.NotTitleHolder() ->
                        player.sendMessage(this.messages.component(
                                MessageKeys.AGENT_INVITE_NOT_TITLEHOLDER, "region", regionId));
            }
        }).exceptionally(ex -> {
            player.sendMessage(this.messages.component(
                    MessageKeys.AGENT_INVITE_ERROR, "error", ex.getMessage()));
            return null;
        });
    }

    @Route("agent invite accept [region]")
    @Permission("realty.command.agent.invite.accept")
    @Description("Accept a pending agent invitation")
    public void accept(@Sender CommandSender rawSender,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player player = playerOrReject(rawSender);
        if (player == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(player, namedRegion);
        if (region == null) {
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        UUID inviteeId = player.getUniqueId();
        this.api.acceptAgentInvite(regionId, worldId, inviteeId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.AcceptAgentInviteResult.Success(UUID inviterId) -> {
                    player.sendMessage(this.messages.component(
                            MessageKeys.AGENT_INVITE_ACCEPT_SUCCESS, "region", regionId));
                    this.events.fireSync(new RealtyNotificationEvent(List.of(inviterId),
                            this.messages.component(MessageKeys.NOTIFICATION_AGENT_INVITE_ACCEPTED,
                                    "player", player.getName(), "region", regionId), region));
                    this.events.fireSync(new AgentInviteAcceptedEvent(region, inviteeId));
                }
                case RealtyBackend.AcceptAgentInviteResult.NotFound() ->
                        player.sendMessage(this.messages.component(
                                MessageKeys.AGENT_INVITE_ACCEPT_NOT_FOUND, "region", regionId));
                case RealtyBackend.AcceptAgentInviteResult.AlreadyAgent() ->
                        player.sendMessage(this.messages.component(
                                MessageKeys.AGENT_INVITE_ACCEPT_ALREADY_AGENT, "region", regionId));
            }
        }).exceptionally(ex -> {
            player.sendMessage(this.messages.component(
                    MessageKeys.AGENT_INVITE_ACCEPT_ERROR, "error", ex.getMessage()));
            return null;
        });
    }

    @Route("agent invite reject [region]")
    @Permission("realty.command.agent.invite.reject")
    @Description("Reject a pending agent invitation")
    public void reject(@Sender CommandSender rawSender,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player player = playerOrReject(rawSender);
        if (player == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(player, namedRegion);
        if (region == null) {
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        UUID inviteeId = player.getUniqueId();
        this.api.rejectAgentInvite(regionId, worldId, inviteeId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.RejectAgentInviteResult.Success(UUID inviterId) -> {
                    player.sendMessage(this.messages.component(
                            MessageKeys.AGENT_INVITE_REJECT_SUCCESS, "region", regionId));
                    this.events.fireSync(new RealtyNotificationEvent(List.of(inviterId),
                            this.messages.component(MessageKeys.NOTIFICATION_AGENT_INVITE_REJECTED,
                                    "player", player.getName(), "region", regionId), region));
                    this.events.fireSync(new AgentInviteRejectedEvent(region, inviteeId));
                }
                case RealtyBackend.RejectAgentInviteResult.NotFound() ->
                        player.sendMessage(this.messages.component(
                                MessageKeys.AGENT_INVITE_REJECT_NOT_FOUND, "region", regionId));
            }
        }).exceptionally(ex -> {
            player.sendMessage(this.messages.component(
                    MessageKeys.AGENT_INVITE_REJECT_ERROR, "error", ex.getMessage()));
            return null;
        });
    }

    @Route("agent invite withdraw <player> [region]")
    @Permission("realty.command.agent.invite.withdraw")
    @Description("Withdraw an agent invitation you sent")
    public void withdraw(@Sender CommandSender rawSender,
                         @Arg("player") PlayerAuthority invitee,
                         @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player player = playerOrReject(rawSender);
        if (player == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(player, namedRegion);
        if (region == null) {
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        UUID inviteeId = invitee.uuid();
        String inviteeName = invitee.name();
        if (!region.region().getOwners().contains(player.getUniqueId())) {
            player.sendMessage(this.messages.component(MessageKeys.AGENT_INVITE_WITHDRAW_NOT_FOUND,
                    "player", inviteeName, "region", regionId));
            return;
        }
        this.api.withdrawAgentInvite(regionId, worldId, inviteeId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.WithdrawAgentInviteResult.Success() -> {
                    player.sendMessage(this.messages.component(MessageKeys.AGENT_INVITE_WITHDRAW_SUCCESS,
                            "player", inviteeName, "region", regionId));
                    this.events.fireSync(new RealtyNotificationEvent(List.of(inviteeId),
                            this.messages.component(MessageKeys.NOTIFICATION_AGENT_INVITE_WITHDRAWN,
                                    "player", nameOf(player.getUniqueId()), "region", regionId), region));
                    this.events.fireSync(new AgentInviteWithdrawnEvent(
                            region, player.getUniqueId(), inviteeId));
                }
                case RealtyBackend.WithdrawAgentInviteResult.NotFound() ->
                        player.sendMessage(this.messages.component(
                                MessageKeys.AGENT_INVITE_WITHDRAW_NOT_FOUND,
                                "player", inviteeName, "region", regionId));
            }
        }).exceptionally(ex -> {
            player.sendMessage(this.messages.component(
                    MessageKeys.AGENT_INVITE_WITHDRAW_ERROR, "error", ex.getMessage()));
            return null;
        });
    }

    @Route("agent remove <player> [region]")
    @Permission("realty.command.agent.remove")
    @Description("Remove an agent from a freehold")
    public void remove(@Sender CommandSender rawSender,
                       @Arg("player") PlayerAuthority target,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        Player player = playerOrReject(rawSender);
        if (player == null) {
            return;
        }
        WorldGuardRegion region = regionOrReject(player, namedRegion);
        if (region == null) {
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        UUID targetId = target.uuid();
        String targetName = target.name();
        if (!region.region().getOwners().contains(player.getUniqueId())) {
            player.sendMessage(this.messages.component(MessageKeys.AGENT_REMOVE_NOT_FOUND,
                    "player", targetName, "region", regionId));
            return;
        }
        UUID actorId = player.getUniqueId();
        this.api.removeSanctionedAuctioneer(regionId, worldId, targetId, actorId).thenAccept(rows -> {
            if (rows > 0) {
                player.sendMessage(this.messages.component(MessageKeys.AGENT_REMOVE_SUCCESS,
                        "player", targetName, "region", regionId));
                this.events.fireSync(new RealtyNotificationEvent(List.of(targetId),
                        this.messages.component(MessageKeys.NOTIFICATION_AGENT_REMOVED,
                                "player", player.getName(), "region", regionId), region));
                this.events.fireSync(new AgentRemovedEvent(region, actorId, targetId));
            } else {
                player.sendMessage(this.messages.component(MessageKeys.AGENT_REMOVE_NOT_FOUND,
                        "player", targetName, "region", regionId));
            }
        }).exceptionally(ex -> {
            player.sendMessage(this.messages.component(
                    MessageKeys.AGENT_REMOVE_ERROR, "error", ex.getMessage()));
            return null;
        });
    }

    /**
     * The sender as a player, or {@code null} after telling the console it cannot run this.
     *
     * <p>Declared as {@code CommandSender} on each route so the console sees Realty's own
     * players-only message rather than the framework's sender-type error.</p>
     */
    private @Nullable Player playerOrReject(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
        return null;
    }

    private @Nullable WorldGuardRegion regionOrReject(@NotNull Player player,
                                                      @Nullable WorldGuardRegion named) {
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(named, player);
        if (region == null) {
            player.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
        }
        return region;
    }

    private static @NotNull String nameOf(@NotNull UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString();
    }
}
