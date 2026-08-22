package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.resolver.MemberName;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.command.util.GroupPrefix;
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
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Handles {@code /realty remove <player> [region]}. */
@Command({"realty", "rl"})
public final class RemoveCommand implements CommandHandler {

    private final Message messages;

    @Inject
    public RemoveCommand(@NotNull Message messages) {
        this.messages = messages;
    }

    @Route("remove <player> [region]")
    @Permission("realty.command.remove")
    @Description("Remove a player or group as a member of a region")
    public void remove(@Sender CommandSender sender,
                    @Arg("player") MemberName playerOrGroup,
                    @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        if (sender instanceof Player player
                && !sender.hasPermission("realty.command.remove.others")
                && !region.region().getOwners().contains(player.getUniqueId())) {
            sender.sendMessage(this.messages.component(MessageKeys.REMOVE_NO_PERMISSION));
            return;
        }
        ProtectedRegion protectedRegion = region.region();
        String group = GroupPrefix.groupNameIfPrefixed(playerOrGroup.value());
        if (group != null) {
            protectedRegion.getMembers().removeGroup(group);
        } else {
            OfflinePlayer target = Bukkit.getOfflinePlayer(playerOrGroup.value());
            protectedRegion.getMembers().removePlayer(target.getUniqueId());
        }
        sender.sendMessage(this.messages.component(MessageKeys.REMOVE_SUCCESS,
                "target", playerOrGroup.value(),
                "region", regionId));
    }
}
