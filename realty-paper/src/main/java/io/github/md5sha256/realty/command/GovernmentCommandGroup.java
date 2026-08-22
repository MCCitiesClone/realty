package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.github.md5sha256.realty.localisation.MessageContainer;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.api.ExecutorState;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.paper.util.sender.Source;
import org.incendo.cloud.parser.standard.StringParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Groups all government-related subcommands under {@code /realty government}.
 *
 * <ul>
 *   <li>{@code /realty government register <account>} — make a Treasury GOVERNMENT account usable
 *       as a region's authority, landlord, title holder or tenant</li>
 *   <li>{@code /realty government list} — show the registered governments</li>
 *   <li>{@code /realty government unregister <name>} — stop offering a government for new
 *       assignments</li>
 * </ul>
 *
 * <p>Registration is a deliberate, separate step rather than something {@code gov:<Name>} does on
 * first use: it needs a Treasury lookup and a database write, and command arguments are parsed on
 * the main thread. Once registered, {@code gov:<Name>} resolves from memory at no cost.
 */
public record GovernmentCommandGroup(
        @NotNull PartyService parties,
        @NotNull ExecutorState executorState,
        @NotNull MessageContainer messages
) implements CustomCommandBean {

    @Override
    public @NotNull List<Command<? extends Source>> commands(@NotNull Command.Builder<Source> builder) {
        var base = builder.literal("government");
        return List.of(
                base.literal("register")
                        .permission("realty.command.government.register")
                        .required("account", StringParser.greedyStringParser())
                        .handler(this::executeRegister)
                        .build(),
                base.literal("list")
                        .permission("realty.command.government.list")
                        .handler(this::executeList)
                        .build(),
                base.literal("unregister")
                        .permission("realty.command.government.unregister")
                        .required("name", StringParser.greedyStringParser(),
                                (ctx, input) -> CompletableFuture.completedFuture(
                                        parties.parties().stream()
                                                .map(GovernmentPartyEntity::displayName)
                                                .map(Suggestion::suggestion)
                                                .toList()))
                        .handler(this::executeUnregister)
                        .build()
        );
    }

    private void executeRegister(@NotNull CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        String account = ctx.get("account");
        if (!parties.treasuryAvailable()) {
            sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_NO_TREASURY));
            return;
        }
        // Treasury lookup plus a database write: kept off the main thread.
        CompletableFuture
                .supplyAsync(() -> parties.registerByName(account), executorState.dbExec())
                .thenAcceptAsync(party -> {
                    if (party.isEmpty()) {
                        sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_NOT_FOUND,
                                Placeholder.unparsed("account", account)));
                        return;
                    }
                    sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_REGISTERED,
                            Placeholder.unparsed("account", party.get().displayName())));
                }, executorState.mainThreadExec())
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_ERROR,
                            Placeholder.unparsed("error", String.valueOf(cause.getMessage()))));
                    return null;
                });
    }

    private void executeList(@NotNull CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        List<GovernmentPartyEntity> registered = parties.parties();
        if (registered.isEmpty()) {
            sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_LIST_EMPTY));
            return;
        }
        sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_LIST_HEADER,
                Placeholder.unparsed("count", String.valueOf(registered.size()))));
        for (GovernmentPartyEntity party : registered) {
            // The party UUID is shown because it is what the default-*-authority-uuid settings
            // take: a server whose plots default to a government needs to paste it in there.
            sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_LIST_ENTRY,
                    Placeholder.unparsed("account", party.displayName()),
                    Placeholder.unparsed("account_id", String.valueOf(party.accountId())),
                    Placeholder.unparsed("uuid", party.partyUuid().toString())));
        }
    }

    private void executeUnregister(@NotNull CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        String name = ctx.get("name");
        var party = parties.partyByName(name);
        if (party.isEmpty()) {
            sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_NOT_REGISTERED,
                    Placeholder.unparsed("account", name)));
            return;
        }
        CompletableFuture
                .supplyAsync(() -> parties.unregister(party.get().partyUuid()), executorState.dbExec())
                .thenAcceptAsync(removed -> sender.sendMessage(removed
                                ? messages.messageFor(MessageKeys.GOVERNMENT_UNREGISTERED,
                                        Placeholder.unparsed("account", party.get().displayName()))
                                : messages.messageFor(MessageKeys.GOVERNMENT_NOT_REGISTERED,
                                        Placeholder.unparsed("account", name))),
                        executorState.mainThreadExec())
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    sender.sendMessage(messages.messageFor(MessageKeys.GOVERNMENT_ERROR,
                            Placeholder.unparsed("error", String.valueOf(cause.getMessage()))));
                    return null;
                });
    }
}
