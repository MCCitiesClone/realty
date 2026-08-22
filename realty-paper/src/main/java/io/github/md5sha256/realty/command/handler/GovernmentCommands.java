package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyService;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code /realty government …} family: registering Treasury governments as parties. */
@Command({"realty", "rl"})
public final class GovernmentCommands implements CommandHandler {

    private final PartyService parties;
    private final ExecutorState executorState;
    private final Message messages;

    @Inject
    public GovernmentCommands(@NotNull PartyService parties,
                              @NotNull ExecutorState executorState,
                              @NotNull Message messages) {
        this.parties = parties;
        this.executorState = executorState;
        this.messages = messages;
    }

    @Route("government register <account>")
    @Permission("realty.command.government.register")
    @Description("Register a Treasury government account as a party")
    public void register(@Sender CommandSender sender,
                         @GreedyArg(value = "account", sanitize = false) String account) {
        if (!this.parties.treasuryAvailable()) {
            sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_NO_TREASURY));
            return;
        }
        // Treasury lookup plus a database write: kept off the main thread.
        CompletableFuture
                .supplyAsync(() -> this.parties.registerByName(account), this.executorState.dbExec())
                .thenAcceptAsync(party -> {
                    if (party.isEmpty()) {
                        sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_NOT_FOUND,
                                "account", account));
                        return;
                    }
                    sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_REGISTERED,
                            "account", party.get().displayName()));
                }, this.executorState.mainThreadExec())
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    
    }

    @Route("government list")
    @Permission("realty.command.government.list")
    @Description("List registered governments")
    public void list(@Sender CommandSender sender) {

                List<GovernmentPartyEntity> registered = this.parties.parties();
        if (registered.isEmpty()) {
            sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_LIST_EMPTY));
            return;
        }
        sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_LIST_HEADER,
                "count", String.valueOf(registered.size())));
        for (GovernmentPartyEntity party : registered) {
            // The party UUID is shown because it is what the default-*-authority-uuid settings
            // take: a server whose plots default to a government needs to paste it in there.
            sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_LIST_ENTRY,
                    "account", party.displayName(),
                    "account_id", String.valueOf(party.accountId()),
                    "uuid", party.partyUuid().toString()));
        }
    
    }

    @Route("government unregister <name>")
    @Permission("realty.command.government.unregister")
    @Description("Unregister a government")
    public void unregister(@Sender CommandSender sender,
                           @GreedyArg(value = "name", sanitize = false) String name) {
        var party = this.parties.partyByName(name);
        if (party.isEmpty()) {
            sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_NOT_REGISTERED,
                    "account", name));
            return;
        }
        CompletableFuture
                .supplyAsync(() -> this.parties.unregister(party.get().partyUuid()), this.executorState.dbExec())
                .thenAcceptAsync(removed -> sender.sendMessage(removed
                                ? this.messages.component(MessageKeys.GOVERNMENT_UNREGISTERED,
                                        "account", party.get().displayName())
                                : this.messages.component(MessageKeys.GOVERNMENT_NOT_REGISTERED,
                                        "account", name)),
                        this.executorState.mainThreadExec())
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    sender.sendMessage(this.messages.component(MessageKeys.GOVERNMENT_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    
    }
}
