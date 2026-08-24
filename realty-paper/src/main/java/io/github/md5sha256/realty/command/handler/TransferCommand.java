package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.TitleTransferEvent;
import io.github.md5sha256.realty.api.event.TitleTransferredEvent;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.command.util.NamedAuthority;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Handles {@code /realty transfer <titleholder> [region]}. */
@Command({"realty", "rl"})
public final class TransferCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public TransferCommand(@NotNull RealtyPaperApi api,
                           @NotNull Message messages,
                           @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("transfer <titleholder> [region]")
    @Permission("realty.command.transfer")
    @Description("Transfer a freehold's title to another party")
    public void transfer(@Sender CommandSender sender,
                         @Arg("titleholder") NamedAuthority titleHolder,
                         @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        UUID titleHolderId = titleHolder.uuid();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        if (sender instanceof Player player
                && !sender.hasPermission("realty.command.transfer.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.TRANSFER_NO_PERMISSION));
            return;
        }
        if (!this.events.fireSync(new TitleTransferEvent(region, titleHolderId))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.transferTitleHolder(region, titleHolderId).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.SetTitleHolderResult.Success success -> {
                    // The resolver already carries the display name, so no second lookup.
                    sender.sendMessage(this.messages.component(MessageKeys.TRANSFER_SUCCESS,
                            "titleholder", titleHolder.name(),
                            "region", success.regionId()));
                    this.events.fireSync(new TitleTransferredEvent(region, titleHolderId,
                            success.previousTitleHolder()));
                }
                case RealtyPaperApi.SetTitleHolderResult.NoFreeholdContract noContract ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.TRANSFER_NO_FREEHOLD_CONTRACT, "region", noContract.regionId()));
                case RealtyPaperApi.SetTitleHolderResult.UpdateFailed updateFailed ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.TRANSFER_UPDATE_FAILED, "region", updateFailed.regionId()));
                case RealtyPaperApi.SetTitleHolderResult.Error error ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.TRANSFER_ERROR, "error", error.message()));
            }
        });
    }
}
