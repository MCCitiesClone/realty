package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.command.SubregionDialog;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.wand.SubregionWand;
import io.github.md5sha256.realty.wand.SubregionWandManager;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code /realty subregion …} family: the selection wand and its confirmation. */
@Command({"realty", "rl"})
public final class SubregionCommands implements CommandHandler {

    private final SubregionWand wand;
    private final SubregionWandManager wandManager;
    private final SubregionDialog dialog;
    private final Message messages;

    @Inject
    public SubregionCommands(@NotNull SubregionWand wand,
                             @NotNull SubregionWandManager wandManager,
                             @NotNull SubregionDialog dialog,
                             @NotNull Message messages) {
        this.wand = wand;
        this.wandManager = wandManager;
        this.dialog = dialog;
        this.messages = messages;
    }

    @Route("subregion wand")
    @Permission("realty.command.subregion.wand")
    @Description("Receive the subregion selection wand")
    public void wand(@Sender CommandSender sender) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        ItemStack item = this.wand.createWand();
        player.getInventory().addItem(item).forEach((index, leftover) ->
                player.getWorld().dropItem(player.getLocation(), leftover));
        player.sendMessage(this.messages.component(MessageKeys.SUBREGION_WAND_GIVEN));
    
    }

    @Route("subregion clear")
    @Permission("realty.command.subregion.wand")
    @Description("Clear your subregion selection")
    public void clear(@Sender CommandSender sender) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        if (this.wandManager.get(player.getUniqueId()) == null) {
            player.sendMessage(this.messages.component(MessageKeys.SUBREGION_NOTHING_TO_CLEAR));
            return;
        }
        this.wandManager.clear(player.getUniqueId());
        player.sendMessage(this.messages.component(MessageKeys.SUBREGION_SELECTION_CLEARED));
    
    }

    @Route("subregion confirm")
    @Permission("realty.command.subregion.confirm")
    @Description("Confirm your subregion selection")
    public void confirm(@Sender CommandSender sender) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        this.dialog.openHeight(player);
    
    }
}
