package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.Realty;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** Handles {@code /realty version}. */
@Command({"realty", "rl"})
public final class VersionCommand implements CommandHandler {

    private final Realty plugin;

    @Inject
    public VersionCommand(@NotNull Realty plugin) {
        this.plugin = plugin;
    }

    @Route("version")
    @Description("Show the installed Realty version")
    public void version(@Sender CommandSender sender) {
        // Read on use rather than at construction: a handler should not depend on how much
        // of the plugin exists when the injector builds it.
        sender.sendMessage(Component.text(
                "Running Realty version " + this.plugin.getPluginMeta().getVersion()));
    }
}
