package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.mapper.RegionTagMapper;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** Handles {@code /realty cleanup tags}. */
@Command({"realty", "rl"})
public final class CleanupCommands implements CommandHandler {

    private final Database database;
    private final ExecutorState executorState;
    private final AtomicReference<RealtyTags> realtyTags;
    private final Message messages;

    @Inject
    public CleanupCommands(@NotNull Database database,
                           @NotNull ExecutorState executorState,
                           @NotNull AtomicReference<RealtyTags> realtyTags,
                           @NotNull Message messages) {
        this.database = database;
        this.executorState = executorState;
        this.realtyTags = realtyTags;
        this.messages = messages;
    }

    @Route("cleanup tags")
    @Permission("realty.command.cleanup.tags")
    @Description("Delete region tags no longer defined in region-tags.yml")
    public void cleanupTags(@Sender CommandSender sender) {

                Set<String> configTagIds = this.realtyTags.get().tagIds();
        CompletableFuture.runAsync(() -> {
            try (SqlSessionWrapper session = this.database.openSession(true)) {
                RegionTagMapper mapper = session.regionTagMapper();
                int deleted;
                if (configTagIds.isEmpty()) {
                    deleted = mapper.deleteAll();
                } else {
                    deleted = mapper.deleteByTagIdNotIn(configTagIds);
                }
                if (deleted == 0) {
                    sender.sendMessage(this.messages.component(MessageKeys.CLEANUP_TAGS_NONE));
                } else {
                    sender.sendMessage(this.messages.component(MessageKeys.CLEANUP_TAGS_SUCCESS,
                            "count", String.valueOf(deleted)));
                }
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.CLEANUP_TAGS_ERROR,
                        "error", ex.getMessage()));
            }
        }, this.executorState.dbExec());
    }
}
