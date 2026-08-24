package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.LoadedModule;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import io.github.md5sha256.realty.Realty;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.command.resolver.ModuleName;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** The {@code /realty module …} family: listing and reloading module jars. */
@Command({"realty", "rl"})
public final class ModuleCommands implements CommandHandler {

    private final ModuleLifecycleManager<Realty> moduleManager;
    private final ExecutorState executorState;
    private final Message messages;

    @Inject
    public ModuleCommands(@NotNull ModuleLifecycleManager<Realty> moduleManager,
                          @NotNull ExecutorState executorState,
                          @NotNull Message messages) {
        this.moduleManager = moduleManager;
        this.executorState = executorState;
        this.messages = messages;
    }

    @Route("module list")
    @Permission("realty.command.module.list")
    @Description("List loaded modules")
    public void list(@Sender CommandSender sender) {

                this.executorState.mainThreadExec().execute(() -> {
            Map<String, LoadedModule<Realty>> active = this.moduleManager.getActiveModules();
            if (active.isEmpty()) {
                sender.sendMessage(this.messages.component(MessageKeys.MODULE_LIST_EMPTY));
                return;
            }
            sender.sendMessage(this.messages.component(MessageKeys.MODULE_LIST_HEADER,
                    "count", String.valueOf(active.size())));
            for (LoadedModule<Realty> module : active.values()) {
                sender.sendMessage(this.messages.component(MessageKeys.MODULE_LIST_ENTRY,
                        "module", module.manifest().moduleName(),
                        "author", module.manifest().author(),
                        "reloadable", String.valueOf(module.manifest().reloadable())));
            }
        });
    }

    @Route("module reload <module>")
    @Permission("realty.command.module.reload")
    @Description("Reload a module")
    public void reload(@Sender CommandSender sender,
                       @Arg("module") ModuleName module) {

                String moduleName = module.value();
        this.executorState.mainThreadExec().execute(() ->
                this.moduleManager.reloadAsync(moduleName).whenComplete((ignored, error) -> {
                    if (error == null) {
                        sender.sendMessage(this.messages.component(MessageKeys.MODULE_RELOAD_SUCCESS,
                                "module", moduleName));
                        return;
                    }
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    sender.sendMessage(this.messages.component(MessageKeys.MODULE_RELOAD_ERROR,
                            "module", moduleName,
                            "error", describe(cause)));
                }));
    }

    

    private static @NotNull String describe(@NotNull Throwable throwable) {
        String message = throwable.getMessage();
        return message != null ? message : throwable.getClass().getSimpleName();
    }
}
