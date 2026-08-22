package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.github.md5sha256.realty.Realty;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.util.SafeLocationFinder;
import io.github.md5sha256.realty.database.entity.RealtySignEntity;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.Settings;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Handles {@code /realty tp <region>}.
 *
 * <p>Prefers WorldGuard's teleport flag, then a safe spot beside a registered sign inside the
 * region, then a search of the region itself — the first of those that is actually safe wins.</p>
 */
@Command({"realty", "rl"})
public final class TeleportCommand implements CommandHandler {

    private static final int SIGN_SEARCH_RADIUS = 3;
    private static final int REGION_MAX_TRIES = 50000;

    private final Logger logger;
    private final RealtyPaperApi api;
    private final AtomicReference<Settings> settings;
    private final Message messages;
    private final SafeLocationFinder safeLocationFinder;

    @Inject
    public TeleportCommand(@NotNull Realty plugin,
                           @NotNull RealtyPaperApi api,
                           @NotNull AtomicReference<Settings> settings,
                           @NotNull Message messages,
                           @NotNull SafeLocationFinder safeLocationFinder) {
        this.logger = plugin.getLogger();
        this.api = api;
        this.settings = settings;
        this.messages = messages;
        this.safeLocationFinder = safeLocationFinder;
    }

    @Route("tp <region>")
    @Permission("realty.command.tp")
    @Description("Teleport to a safe spot inside a region")
    public void teleport(@Sender CommandSender rawSender,
                         @Arg("region") WorldGuardRegion region) {
        // Declared as CommandSender rather than Player so the console gets Realty's own
        // players-only message; @Sender Player would have the framework reject it with its.
        if (!(rawSender instanceof Player player)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
                String regionId = region.region().getId();
        UUID worldId = region.world().getUID();

        ProtectedRegion protectedRegion = region.region();
        com.sk89q.worldedit.util.Location flagLoc = protectedRegion.getFlag(Flags.TELE_LOC);

        if (flagLoc != null) {
            if (this.safeLocationFinder.isSafe(region.world(),
                    flagLoc.getBlockX(),
                    flagLoc.getBlockY(),
                    flagLoc.getBlockZ())) {
                processTeleport(player,
                        regionId,
                        new Location(region.world(),
                                flagLoc.getBlockX(),
                                flagLoc.getBlockY(),
                                flagLoc.getBlockZ()),
                        null);
            } else  {
                this.logger.warning("WG Flag unsafe teleport location for region: " + regionId);
            }
        }

        this.api.listSigns(regionId, worldId).thenCompose(signs -> {
            // Build an async search chain: try each sign inside the region, then fall back
            CompletableFuture<Location> search = CompletableFuture.completedFuture(null);
            for (RealtySignEntity sign : signs) {
                if (!region.region().contains(BlockVector3.at(
                        sign.blockX(), sign.blockY(), sign.blockZ()))) {
                    continue;
                }
                search = search.thenCompose(loc -> {
                    if (loc != null) {
                        return CompletableFuture.completedFuture(loc);
                    }
                    World signWorld = Bukkit.getWorld(sign.worldId());
                    if (signWorld == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return this.safeLocationFinder.findSafeNearSign(
                            signWorld, sign.blockX(), sign.blockY(), sign.blockZ(),
                            SIGN_SEARCH_RADIUS);
                });
            }
            return search;
        }).thenCompose(loc -> {
            if (loc != null) {
                return CompletableFuture.completedFuture(loc);
            }
            return this.safeLocationFinder.findSafeInRegion(
                    region.region(), region.world(), REGION_MAX_TRIES, this.settings.get().teleportStartHeight());
        }).whenComplete((loc, ex) -> processTeleport(player, regionId, loc, ex));
    }

    private void processTeleport(@NotNull Player player,
                                 @NotNull String regionId,
                                 @Nullable Location loc,
                                 @Nullable Throwable ex) {
        if (!player.isOnline()) {
            return;
        }
        if (ex != null) {
            ex.printStackTrace();
            player.sendMessage(this.messages.component(MessageKeys.TP_ERROR,
                    "error", String.valueOf(ex.getMessage())));
            return;
        }
        if (loc != null) {
            player.teleportAsync(loc);
            player.sendMessage(this.messages.component(MessageKeys.TP_SUCCESS,
                    "region", regionId));
        } else {
            player.sendMessage(this.messages.component(MessageKeys.TP_NO_SAFE_LOCATION,
                    "region", regionId));
        }
    }
}
