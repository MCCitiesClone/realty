package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.mapper.RegionTagMapper;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.RealtyTags;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public record CleanupCommandGroup(
        @NotNull Database database,
        @NotNull ExecutorState executorState,
        @NotNull AtomicReference<RealtyTags> realtyTags,
        @NotNull Message messages
) implements CustomCommandBean {

    @Override
    public @NotNull List<Command<? extends Source>> commands(@NotNull Command.Builder<Source> builder) {
        Command<? extends Source> cleanupTags = builder
                .literal("cleanup")
                .literal("tags")
                .permission("realty.command.cleanup.tags")
                .handler(this::executeCleanupTags)
                .build();
        return List.of(cleanupTags);
    }

    private void executeCleanupTags(@NotNull CommandContext<Source> ctx) {
        CommandSender sender = ctx.sender().source();
        Set<String> configTagIds = realtyTags.get().tagIds();
        CompletableFuture.runAsync(() -> {
            try (SqlSessionWrapper session = database.openSession(true)) {
                RegionTagMapper mapper = session.regionTagMapper();
                int deleted;
                if (configTagIds.isEmpty()) {
                    deleted = mapper.deleteAll();
                } else {
                    deleted = mapper.deleteByTagIdNotIn(configTagIds);
                }
                if (deleted == 0) {
                    sender.sendMessage(messages.component(MessageKeys.CLEANUP_TAGS_NONE));
                } else {
                    sender.sendMessage(messages.component(MessageKeys.CLEANUP_TAGS_SUCCESS,
                            "count", String.valueOf(deleted)));
                }
            } catch (Exception ex) {
                sender.sendMessage(messages.component(MessageKeys.CLEANUP_TAGS_ERROR,
                        "error", ex.getMessage()));
            }
        }, executorState.dbExec());
    }

}
