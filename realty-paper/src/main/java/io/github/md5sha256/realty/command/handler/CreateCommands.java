package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.SessionManager;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionCreateEvent;
import io.github.md5sha256.realty.api.event.RegionCreatedEvent;
import io.github.md5sha256.realty.command.util.NamedAuthority;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.settings.Settings;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /realty create …} family: making the WorldGuard region from the player's
 * selection and registering it with Realty in one step.
 *
 * <p>Distinct from {@code /realty register}, which takes a region that already exists.</p>
 */
@Command({"realty", "rl"})
public final class CreateCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final AtomicReference<Settings> settings;
    private final Message messages;
    private final RealtyEventDispatch events;
    private final PartyService parties;

    @Inject
    public CreateCommands(@NotNull RealtyPaperApi api,
                          @NotNull AtomicReference<Settings> settings,
                          @NotNull Message messages,
                          @NotNull RealtyEventDispatch events,
                          @NotNull PartyService parties) {
        this.api = api;
        this.settings = settings;
        this.messages = messages;
        this.events = events;
        this.parties = parties;
    }

    @Route("create leasehold <name> <price> <period> <maxextensions>")
    @Permission("realty.command.create.leasehold")
    @Description("Create a region from your selection and list it for lease")
    public void leasehold(@Sender CommandSender rawSender,
                          @Arg("name") String name,
                          @Arg("price") double price,
                          @Arg("period") Duration period,
                          @Arg("maxextensions") int maxExtensions,
                          @Flag("landlord") @Nullable NamedAuthority landlordFlag) {

        if (!(rawSender instanceof Player player)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        if (!VALID_NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(this.messages.component(MessageKeys.CREATE_INVALID_NAME,
                    "region", name));
            return;
        }
        UUID landlord = landlordFlag != null ? landlordFlag.uuid()
                : this.settings.get().defaultLeaseholdAuthority();

        RegionManager regionManager = getRegionManager(player.getWorld());
        if (regionManager == null) {
            player.sendMessage(this.messages.component(MessageKeys.COMMON_ERROR,
                    "error", "Region manager unavailable"));
            return;
        }
        if (regionManager.getRegion(name) != null) {
            player.sendMessage(this.messages.component(MessageKeys.CREATE_REGION_EXISTS,
                    "region", name));
            return;
        }
        Region selection = getSelection(player);
        if (selection == null) {
            player.sendMessage(this.messages.component(MessageKeys.CREATE_INCOMPLETE_SELECTION));
            return;
        }

        ProtectedRegion wgRegion = createProtectedRegion(name, selection);
        World world = player.getWorld();
        WorldGuardRegion region = new WorldGuardRegion(wgRegion, world);
        if (!this.events.fireSync(new RegionCreateEvent(region, player.getUniqueId()))) {
            player.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        regionManager.addRegion(wgRegion);

        this.api.createLeasehold(region, price, period.toSeconds(), maxExtensions, landlord)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyPaperApi.CreateLeaseholdResult.Success ignored -> {
                                player.sendMessage(this.messages.component(MessageKeys.CREATE_LEASEHOLD_SUCCESS,
                                        "region", name));
                                this.events.fireSync(new RegionCreatedEvent(region, player.getUniqueId()));
                        }
                        case RealtyPaperApi.CreateLeaseholdResult.AlreadyRegistered ignored -> {
                            regionManager.removeRegion(name);
                            player.sendMessage(this.messages.component(MessageKeys.CREATE_ALREADY_REGISTERED,
                                    "region", name));
                        }
                        case RealtyPaperApi.CreateLeaseholdResult.Error error ->
                                player.sendMessage(this.messages.component(MessageKeys.CREATE_ERROR,
                                        "error", error.message()));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    player.sendMessage(this.messages.component(MessageKeys.CREATE_ERROR,
                            "error", cause.getMessage()));
                    return null;
                });
    
    }

    @Route("create freehold <name>")
    @Permission("realty.command.create.freehold")
    @Description("Create a region from your selection and list it for sale")
    public void freehold(@Sender CommandSender rawSender,
                         @Arg("name") String name,
                         @Flag("price") @Nullable Double priceFlag,
                         @Flag("titleholder") @Nullable NamedAuthority titleHolderFlag,
                         @Flag("authority") @Nullable NamedAuthority authorityFlag) {

        if (!(rawSender instanceof Player player)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        if (!VALID_NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(this.messages.component(MessageKeys.CREATE_INVALID_NAME,
                    "region", name));
            return;
        }
        Double price = priceFlag;
        UUID authority = authorityFlag != null ? authorityFlag.uuid()
                : this.settings.get().defaultFreeholdAuthority();
        UUID titleholder = titleHolderFlag != null ? titleHolderFlag.uuid()
                : this.settings.get().defaultFreeholdTitleholder();

        RegionManager regionManager = getRegionManager(player.getWorld());
        if (regionManager == null) {
            player.sendMessage(this.messages.component(MessageKeys.COMMON_ERROR,
                    "error", "Region manager unavailable"));
            return;
        }
        if (regionManager.getRegion(name) != null) {
            player.sendMessage(this.messages.component(MessageKeys.CREATE_REGION_EXISTS,
                    "region", name));
            return;
        }
        Region selection = getSelection(player);
        if (selection == null) {
            player.sendMessage(this.messages.component(MessageKeys.CREATE_INCOMPLETE_SELECTION));
            return;
        }

        ProtectedRegion wgRegion = createProtectedRegion(name, selection);
        World world = player.getWorld();
        WorldGuardRegion region = new WorldGuardRegion(wgRegion, world);
        if (!this.events.fireSync(new RegionCreateEvent(region, player.getUniqueId()))) {
            player.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        regionManager.addRegion(wgRegion);

        this.api.createFreehold(region, price, authority, titleholder)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyPaperApi.CreateFreeholdResult.Success ignored -> {
                                player.sendMessage(this.messages.component(MessageKeys.CREATE_FREEHOLD_SUCCESS,
                                        "region", name));
                                this.events.fireSync(new RegionCreatedEvent(region, player.getUniqueId()));
                        }
                        case RealtyPaperApi.CreateFreeholdResult.AlreadyRegistered ignored -> {
                            regionManager.removeRegion(name);
                            player.sendMessage(this.messages.component(MessageKeys.CREATE_ALREADY_REGISTERED,
                                    "region", name));
                        }
                        case RealtyPaperApi.CreateFreeholdResult.Error error ->
                                player.sendMessage(this.messages.component(MessageKeys.CREATE_ERROR,
                                        "error", error.message()));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    player.sendMessage(this.messages.component(MessageKeys.CREATE_ERROR,
                            "error", cause.getMessage()));
                    return null;
                });
    
    }

    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9-]+$");

    private static RegionManager getRegionManager(@NotNull World world) {
        RegionContainer regionContainer = WorldGuard.getInstance()
                .getPlatform()
                .getRegionContainer();
        return regionContainer.get(BukkitAdapter.adapt(world));
    }

    private static Region getSelection(@NotNull Player player) {
        SessionManager sessionManager = WorldEdit.getInstance().getSessionManager();
        LocalSession localSession = sessionManager.get(BukkitAdapter.adapt(player));
        if (!Objects.equals(localSession.getSelectionWorld(), BukkitAdapter.adapt(player.getWorld()))) {
            return null;
        }
        try {
            return localSession.getSelection().clone();
        } catch (IncompleteRegionException ex) {
            return null;
        }
    }

    private static @NotNull ProtectedRegion createProtectedRegion(@NotNull String name,
                                                                    @NotNull Region selection) {
        if (selection instanceof CuboidRegion cuboid) {
            return new ProtectedCuboidRegion(name,
                    cuboid.getMinimumPoint(), cuboid.getMaximumPoint());
        } else if (selection instanceof Polygonal2DRegion polygon) {
            return new ProtectedPolygonalRegion(name,
                    polygon.getPoints(), polygon.getMinimumY(), polygon.getMaximumY());
        }
        return new ProtectedCuboidRegion(name,
                selection.getMinimumPoint(), selection.getMaximumPoint());
    }
}
