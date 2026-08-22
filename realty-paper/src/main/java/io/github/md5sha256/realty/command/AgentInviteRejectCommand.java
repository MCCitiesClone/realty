package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.AgentInviteRejectedEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.command.util.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import org.incendo.cloud.paper.util.sender.Source;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Handles {@code /realty agent invite reject <region>}.
 *
 * <p>Rejects a pending agent invite, removing the invite without adding the player
 * as a sanctioned auctioneer.</p>
 *
 * <p>Permission: {@code realty.command.agent.invite.reject}.</p>
 */
public record AgentInviteRejectCommand(@NotNull RealtyPaperApi api,
                                        @NotNull Message messages,
                                        @NotNull RealtyEventDispatch events) implements CustomCommandBean.Single {

    @Override
    public @NotNull Command<? extends Source> command(@NotNull Command.Builder<Source> builder) {
        return builder
                .literal("agent")
                .literal("invite")
                .literal("reject")
                .permission("realty.command.agent.invite.reject")
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
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(player.getLocation()));
        if (region == null) {
            player.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();
        UUID inviteeId = player.getUniqueId();
        api.rejectAgentInvite(regionId, worldId, inviteeId).thenAccept(result -> {
            switch (result) {
                case RealtyBackend.RejectAgentInviteResult.Success(UUID inviterId) -> {
                    sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_REJECT_SUCCESS,
                            "region", regionId));
                    events.fireSync(new RealtyNotificationEvent(List.of(inviterId),
                            messages.component(MessageKeys.NOTIFICATION_AGENT_INVITE_REJECTED,
                                    "player", player.getName(),
                                    "region", regionId), region));
                    events.fireSync(new AgentInviteRejectedEvent(region, inviteeId));
                }
                case RealtyBackend.RejectAgentInviteResult.NotFound() ->
                        sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_REJECT_NOT_FOUND,
                                "region", regionId));
            }
        }).exceptionally(ex -> {
            sender.sendMessage(messages.component(MessageKeys.AGENT_INVITE_REJECT_ERROR,
                    "error", ex.getMessage()));
            return null;
        });
    }
}
