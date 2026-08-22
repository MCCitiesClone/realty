package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.TitleTransferEvent;
import io.github.md5sha256.realty.api.event.TitleTransferredEvent;
import io.github.md5sha256.realty.command.util.AuthorityParser;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.command.util.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.party.PartyNames;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Handles {@code /realty transfer <titleholder> [region]}.
 *
 * <p>Transfers freehold ownership to the given player and clears the listed price.
 * Equivalent to {@code /realty set titleholder} followed by clearing the price atomically.</p>
 *
 * <p>Permission: {@code realty.command.transfer} (or {@code realty.command.transfer.others}
 * to transfer regions you don't own).</p>
 */
public record TransferCommand(
        @NotNull RealtyPaperApi api,
        @NotNull Message messages,
        @NotNull RealtyEventDispatch events,
        @NotNull PartyService parties
) implements CustomCommandBean.Single {

    private @NotNull String resolveName(@NotNull UUID uuid) {
        return PartyNames.resolve(parties, uuid);
    }

    @Override
    public @NotNull Command<? extends Source> command(@NotNull Command.Builder<Source> builder) {
        return builder
                .literal("transfer")
                .permission("realty.command.transfer")
                .required("titleholder", AuthorityParser.party(parties))
                .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                .handler(this::execute)
                .build();
    }

    private void execute(@NotNull CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        UUID titleHolderId = ctx.get("titleholder");
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> sender instanceof Player player
                        ? WorldGuardRegionResolver.resolveAtLocation(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        if (sender instanceof Player player
                && !sender.hasPermission("realty.command.transfer.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(messages.component(MessageKeys.TRANSFER_NO_PERMISSION));
            return;
        }
        if (!events.fireSync(new TitleTransferEvent(region, titleHolderId))) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        api.transferTitleHolder(region, titleHolderId).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.SetTitleHolderResult.Success success -> {
                        sender.sendMessage(messages.component(MessageKeys.TRANSFER_SUCCESS,
                                "titleholder", resolveName(titleHolderId),
                                "region", success.regionId()));
                        events.fireSync(new TitleTransferredEvent(region, titleHolderId,
                                success.previousTitleHolder()));
                }
                case RealtyPaperApi.SetTitleHolderResult.NoFreeholdContract noContract ->
                        sender.sendMessage(messages.component(MessageKeys.TRANSFER_NO_FREEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.SetTitleHolderResult.UpdateFailed updateFailed ->
                        sender.sendMessage(messages.component(MessageKeys.TRANSFER_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.SetTitleHolderResult.Error error ->
                        sender.sendMessage(messages.component(MessageKeys.TRANSFER_ERROR,
                                "error", error.message()));
            }
        });
    }
}
