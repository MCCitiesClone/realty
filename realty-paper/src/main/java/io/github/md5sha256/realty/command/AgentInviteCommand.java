package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.command.util.AuthorityParser;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.AgentInviteEvent;
import io.github.md5sha256.realty.api.event.AgentInvitedEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.command.util.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import org.incendo.cloud.paper.util.sender.Source;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Handles {@code /realty agent invite <player> <region>}.
 *
 * <p>Invites a player as a sanctioned auctioneer for a region.
 * Only the title holder can send invites.</p>
 *
 * <p>Permission: {@code realty.command.agent.invite}.</p>
 */
public record AgentInviteCommand(@NotNull RealtyPaperApi api,
                                  @NotNull Message messages,
                                  @NotNull RealtyEventDispatch events) implements CustomCommandBean.Single {

    @Override
    public @NotNull Command<? extends Source> command(@NotNull Command.Builder<Source> builder) {
        return builder
                .literal("agent")
                .literal("invite")
                .permission("realty.command.agent.invite")
                .required("player", AuthorityParser.authority())
                .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                .handler(this::execute)
                .build();
    }

    private void execute(@NotNull CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        UUID inviteeId = ctx.get("player");
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(player.getLocation()));
        if (region == null) {
            player.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        String inviteeName = resolveName(inviteeId);
        if (!region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_NOT_TITLEHOLDER,
                    "region", regionId));
            return;
        }
        if (!events.fireSync(new AgentInviteEvent(region, player.getUniqueId(), inviteeId))) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        api.inviteAgent(regionId, worldId, player.getUniqueId(), inviteeId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.InviteAgentResult.Success() -> {
                    sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_SUCCESS,
                            "player", inviteeName,
                            "region", regionId));
                    events.fireSync(new RealtyNotificationEvent(List.of(inviteeId),
                            messages.component(MessageKeys.NOTIFICATION_AGENT_INVITED,
                                    "player", player.getName(),
                                    "region", regionId), region));
                    events.fireSync(new AgentInvitedEvent(region, player.getUniqueId(), inviteeId));
                }
                case RealtyBackend.InviteAgentResult.NoFreeholdContract() ->
                        sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_NO_FREEHOLD,
                                "region", regionId));
                case RealtyBackend.InviteAgentResult.IsTitleHolder() ->
                        sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_IS_TITLEHOLDER,
                                "player", inviteeName,
                                "region", regionId));
                case RealtyBackend.InviteAgentResult.IsAuthority() ->
                        sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_IS_AUTHORITY,
                                "player", inviteeName,
                                "region", regionId));
                case RealtyBackend.InviteAgentResult.AlreadyAgent() ->
                        sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_ALREADY_AGENT,
                                "player", inviteeName,
                                "region", regionId));
                case RealtyBackend.InviteAgentResult.AlreadyInvited() ->
                        sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_ALREADY_INVITED,
                                "player", inviteeName,
                                "region", regionId));
                case RealtyBackend.InviteAgentResult.NotTitleHolder() ->
                        sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_NOT_TITLEHOLDER,
                                "region", regionId));
            }
        }).exceptionally(ex -> {
            sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_ERROR,
                    "error", ex.getMessage()));
            return null;
        });
    }

    private static @NotNull String resolveName(@NotNull UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString();
    }
}
