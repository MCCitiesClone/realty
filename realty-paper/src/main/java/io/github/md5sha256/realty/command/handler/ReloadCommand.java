package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.Realty;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/** Handles {@code /realty reload}. */
@Command({"realty", "rl"})
public final class ReloadCommand implements CommandHandler {

    private final Realty plugin;
    private final ExecutorState executorState;
    private final Message messages;

    @Inject
    public ReloadCommand(@NotNull Realty plugin,
                         @NotNull ExecutorState executorState,
                         @NotNull Message messages) {
        this.plugin = plugin;
        this.executorState = executorState;
        this.messages = messages;
    }

    @Route("reload")
    @Permission("realty.command.reload")
    @Description("Reload Realty's configuration and messages")
    public void reload(@Sender CommandSender sender) {
        // Off the main thread: a reload re-reads every config file and refreshes the party cache.
        CompletableFuture.runAsync(() -> {
            try {
                this.plugin.performReload();
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.RELOAD_ERROR,
                        "error", ex.getMessage()));
                return;
            }
            sender.sendMessage(this.messages.component(MessageKeys.RELOAD_SUCCESS));
        }, this.executorState.dbExec());
    }
}
