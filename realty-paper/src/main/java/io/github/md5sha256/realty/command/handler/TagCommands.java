package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.resolver.TagId;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.mapper.RegionTagMapper;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.ConfigRegionTag;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code /realty tag …} family: applying, removing and listing region tags. */
@Command({"realty", "rl"})
public final class TagCommands implements CommandHandler {

    private final Database database;
    private final ExecutorState executorState;
    private final AtomicReference<RealtyTags> realtyTags;
    private final Message messages;

    @Inject
    public TagCommands(@NotNull Database database,
                       @NotNull ExecutorState executorState,
                       @NotNull AtomicReference<RealtyTags> realtyTags,
                       @NotNull Message messages) {
        this.database = database;
        this.executorState = executorState;
        this.realtyTags = realtyTags;
        this.messages = messages;
    }

    @Route("tag add <tag> [region]")
    @Permission("realty.command.tag.add")
    @Description("Add a tag to a region")
    public void add(@Sender CommandSender sender,
                    @Arg("tag") TagId tag,
                    @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                String tagId = tag.value();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        ConfigRegionTag configTag = this.realtyTags.get().get(tagId);
        if (configTag == null) {
            sender.sendMessage(this.messages.component(MessageKeys.TAG_UNKNOWN,
                    "tag", tagId));
            return;
        }
        if (configTag.permission() != null && !sender.hasPermission(configTag.permission().node())) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_NO_PERMISSION));
            return;
        }
        String regionId = region.region().getId();
        CompletableFuture.runAsync(() -> {
            try (SqlSessionWrapper session = this.database.openSession(true)) {
                RegionTagMapper mapper = session.regionTagMapper();
                if (mapper.exists(tagId, regionId)) {
                    sender.sendMessage(this.messages.component(MessageKeys.TAG_ADD_ALREADY_TAGGED,
                            "tag", tagId,
                            "region", regionId));
                    return;
                }
                int inserted = mapper.insert(tagId, regionId);
                if (inserted > 0) {
                    sender.sendMessage(this.messages.component(MessageKeys.TAG_ADD_SUCCESS,
                            "tag", tagId,
                            "region", regionId));
                } else {
                    sender.sendMessage(this.messages.component(MessageKeys.TAG_ADD_FAILED,
                            "tag", tagId,
                            "region", regionId));
                }
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.TAG_ERROR,
                        "error", ex.getMessage()));
            }
        }, this.executorState.dbExec());
    }

    @Route("tag remove <tag> [region]")
    @Permission("realty.command.tag.remove")
    @Description("Remove a tag from a region")
    public void remove(@Sender CommandSender sender,
                       @Arg("tag") TagId tag,
                       @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                String tagId = tag.value();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        ConfigRegionTag configTag = this.realtyTags.get().get(tagId);
        if (configTag == null) {
            sender.sendMessage(this.messages.component(MessageKeys.TAG_UNKNOWN,
                    "tag", tagId));
            return;
        }
        if (configTag.permission() != null && !sender.hasPermission(configTag.permission().node())) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_NO_PERMISSION));
            return;
        }
        String regionId = region.region().getId();
        CompletableFuture.runAsync(() -> {
            try (SqlSessionWrapper session = this.database.openSession(true)) {
                RegionTagMapper mapper = session.regionTagMapper();
                int deleted = mapper.deleteByTagAndRegion(tagId, regionId);
                if (deleted > 0) {
                    sender.sendMessage(this.messages.component(MessageKeys.TAG_REMOVE_SUCCESS,
                            "tag", tagId,
                            "region", regionId));
                } else {
                    sender.sendMessage(this.messages.component(MessageKeys.TAG_REMOVE_NOT_FOUND,
                            "tag", tagId,
                            "region", regionId));
                }
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.TAG_ERROR,
                        "error", ex.getMessage()));
            }
        }, this.executorState.dbExec());
    }

    @Route("tag clear [region]")
    @Permission("realty.command.tag.clear")
    @Description("Clear every tag from a region")
    public void clear(@Sender CommandSender sender,
                      @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        CompletableFuture.runAsync(() -> {
            try (SqlSessionWrapper session = this.database.openSession(true)) {
                int deleted = session.regionTagMapper().deleteByRegionId(regionId);
                if (deleted > 0) {
                    sender.sendMessage(this.messages.component(MessageKeys.TAG_CLEAR_SUCCESS,
                            "count", String.valueOf(deleted),
                            "region", regionId));
                } else {
                    sender.sendMessage(this.messages.component(MessageKeys.TAG_CLEAR_NONE,
                            "region", regionId));
                }
            } catch (Exception ex) {
                sender.sendMessage(this.messages.component(MessageKeys.TAG_ERROR,
                        "error", ex.getMessage()));
            }
        }, this.executorState.dbExec());
    }

    @Route("tag list")
    @Permission("realty.command.tag.list")
    @Description("List the configured region tags")
    public void list(@Sender CommandSender sender) {

                List<ConfigRegionTag> permitted = this.realtyTags.get().values().stream()
                .filter(tag -> tag.permission() == null || sender.hasPermission(tag.permission().node()))
                .toList();
        if (permitted.isEmpty()) {
            sender.sendMessage(this.messages.component(MessageKeys.TAG_LIST_NONE));
            return;
        }
        TextComponent.Builder builder = Component.text();
        builder.append(this.messages.component(MessageKeys.TAG_LIST_HEADER));
        for (ConfigRegionTag tag : permitted) {
            builder.appendNewline();
            builder.append(this.messages.component(MessageKeys.TAG_LIST_ENTRY,
                    "tag", tag.tagId(),
                    "display", tag.tagDisplayName()));
        }
        sender.sendMessage(builder.build());
    }
}
