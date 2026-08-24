package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionDeleteEvent;
import io.github.md5sha256.realty.api.event.RegionDeletedEvent;
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

/**
 * Handles {@code /realty delete <region> [includeworldguard]}.
 *
 * <p>The region is required here rather than falling back to the one the sender stands in:
 * deleting the wrong region because of where someone happened to be standing is not recoverable.</p>
 */
@Command({"realty", "rl"})
public final class DeleteCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public DeleteCommand(@NotNull RealtyPaperApi api,
                         @NotNull Message messages,
                         @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.messages = messages;
        this.events = events;
    }

    @Route("delete <region> [includeworldguard]")
    @Permission("realty.command.delete")
    @Description("Delete a region's Realty registration")
    public void delete(@Sender CommandSender sender,
                       @Arg("region") WorldGuardRegion region,
                       @OptionalArg(value = "includeworldguard", defaultValue = "false")
                       boolean includeWorldGuard) {
        if (includeWorldGuard && !sender.hasPermission("realty.command.delete.includeworldguard")) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_NO_PERMISSION));
            return;
        }
        if (sender instanceof Player player
                && !this.events.fireSync(new RegionDeleteEvent(region, player.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.deleteRegion(region, includeWorldGuard).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.DeleteResult.Success ignored -> {
                    sender.sendMessage(this.messages.component(MessageKeys.DELETE_SUCCESS));
                    if (sender instanceof Player player) {
                        this.events.fireSync(new RegionDeletedEvent(region, player.getUniqueId()));
                    }
                }
                case RealtyPaperApi.DeleteResult.NotRegistered ignored ->
                        sender.sendMessage(this.messages.component(MessageKeys.DELETE_NOT_REGISTERED));
                case RealtyPaperApi.DeleteResult.WorldGuardSaveError wgError ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.DELETE_WORLDGUARD_SAVE_ERROR, "error", wgError.error()));
                case RealtyPaperApi.DeleteResult.Error error ->
                        sender.sendMessage(this.messages.component(
                                MessageKeys.DELETE_ERROR, "error", error.message()));
            }
        });
    }
}
