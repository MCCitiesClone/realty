package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.SignTextApplicator;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.database.entity.RealtySignEntity;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyService;
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
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The {@code /realty sign …} family: registering, removing and listing region signs. */
@Command({"realty", "rl"})
public final class SignCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final ExecutorState executorState;
    private final Message messages;
    private final PartyService parties;

    @Inject
    public SignCommands(@NotNull RealtyPaperApi api,
                        @NotNull ExecutorState executorState,
                        @NotNull Message messages,
                        @NotNull PartyService parties) {
        this.api = api;
        this.executorState = executorState;
        this.messages = messages;
        this.parties = parties;
    }

    @Route("sign place <region>")
    @Permission("realty.command.sign.place")
    @Description("Register the sign you are looking at")
    public void place(@Sender CommandSender sender,
                      @Arg("region") WorldGuardRegion region) {

                if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null || !(targetBlock.getState() instanceof Sign)) {
            sender.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_NOT_A_SIGN));
            return;
        }
        // The player must be able to build where the sign physically sits, so signs cannot
        // be registered inside a region the player has no access to.
        if (!canBuildAt(player, targetBlock)) {
            sender.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_NO_BUILD_ACCESS));
            return;
        }
        String regionId = region.region().getId();
        int blockX = targetBlock.getX();
        int blockY = targetBlock.getY();
        int blockZ = targetBlock.getZ();
        UUID signWorldId = targetBlock.getWorld().getUID();

        // Staff with the bypass permission may place signs for any region; everyone else
        // may only place signs for regions they are the landlord of.
        boolean canBypass = player.hasPermission(BYPASS_PERMISSION);
        if (canBypass) {
            placeSign(player, region, regionId, signWorldId, blockX, blockY, blockZ);
            return;
        }
        UUID worldId = region.world().getUID();
        this.api.getLeaseholdContract(regionId, worldId)
                .thenAccept(lease -> {
                    if (lease == null || !this.parties.actsFor(player.getUniqueId(), lease.landlordId())) {
                        player.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_NOT_LANDLORD,
                                "region", regionId));
                        return;
                    }
                    placeSign(player, region, regionId, signWorldId, blockX, blockY, blockZ);
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    player.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    }

    @Route("sign remove")
    @Permission("realty.command.sign.remove")
    @Description("Unregister the sign you are looking at")
    public void remove(@Sender CommandSender sender) {

                if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null || !(targetBlock.getState() instanceof Sign)) {
            sender.sendMessage(this.messages.component(MessageKeys.SIGN_REMOVE_NOT_A_SIGN));
            return;
        }
        int blockX = targetBlock.getX();
        int blockY = targetBlock.getY();
        int blockZ = targetBlock.getZ();
        UUID signWorldId = targetBlock.getWorld().getUID();
        Sign sign = (Sign) targetBlock.getState();

        this.api.removeSign(signWorldId, blockX, blockY, blockZ)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyPaperApi.RemoveSignResult.Success ignored -> {
                            this.executorState.mainThreadExec().execute(
                                    () -> SignTextApplicator.clearLines(sign));
                            sender.sendMessage(this.messages.component(MessageKeys.SIGN_REMOVE_SUCCESS));
                        }
                        case RealtyPaperApi.RemoveSignResult.NotRegistered ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.SIGN_REMOVE_NOT_REGISTERED));
                        case RealtyPaperApi.RemoveSignResult.Error error ->
                                sender.sendMessage(this.messages.component(MessageKeys.SIGN_REMOVE_ERROR,
                                        "error", error.message()));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    sender.sendMessage(this.messages.component(MessageKeys.SIGN_REMOVE_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    }

    @Route("sign list [region]")
    @Permission("realty.command.sign.list")
    @Description("List a region's registered signs")
    public void list(@Sender CommandSender sender,
                     @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, player);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        String regionId = region.region().getId();
        UUID worldId = region.world().getUID();

        this.api.listSigns(regionId, worldId)
                .thenAccept(signs -> {
                    if (signs.isEmpty()) {
                        sender.sendMessage(this.messages.component(MessageKeys.SIGN_LIST_NO_SIGNS,
                                "region", regionId));
                        return;
                    }
                    sender.sendMessage(this.messages.component(MessageKeys.SIGN_LIST_HEADER,
                            "region", regionId));
                    for (RealtySignEntity signEntity : signs) {
                        World signWorld = Bukkit.getWorld(signEntity.worldId());
                        String worldName = signWorld != null
                                ? signWorld.getName() : signEntity.worldId().toString();
                        sender.sendMessage(signListEntry(worldName, signEntity));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    sender.sendMessage(this.messages.component(MessageKeys.SIGN_LIST_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    }

    

    private static final String BYPASS_PERMISSION = "realty.command.sign.place.bypass";

    private void placeSign(@NotNull Player sender, @NotNull WorldGuardRegion region,
                           @NotNull String regionId, @NotNull UUID signWorldId,
                           int blockX, int blockY, int blockZ) {
        this.api.placeSign(region, signWorldId, blockX, blockY, blockZ)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyPaperApi.PlaceSignResult.Success ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_SUCCESS,
                                        "region", regionId));
                        case RealtyPaperApi.PlaceSignResult.NotRegistered ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_NOT_REGISTERED,
                                        "region", regionId));
                        case RealtyPaperApi.PlaceSignResult.Error error ->
                                sender.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_ERROR,
                                        "error", error.message()));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    sender.sendMessage(this.messages.component(MessageKeys.SIGN_PLACE_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    }

    /**
     * Tests whether the player is allowed to build at the given block, honouring WorldGuard
     * region membership and the {@code BUILD} flag (and the WorldGuard bypass permission).
     */
    private boolean canBuildAt(@NotNull Player player, @NotNull Block block) {
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(block.getWorld());
        if (WorldGuard.getInstance().getPlatform().getSessionManager()
                .hasBypass(localPlayer, weWorld)) {
            return true;
        }
        RegionQuery query = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().createQuery();
        return query.testBuild(BukkitAdapter.adapt(block.getLocation()), localPlayer, Flags.BUILD);
    }

    /**
     * Renders one {@code /realty sign list} row.
     *
     * <p>The coordinates appear twice in that message: once as visible text and once inside the
     * {@code <click:run_command:'/tp @s ...'>} argument. A resolver cannot reach inside a tag
     * argument, so the teleport command was previously emitted with the placeholders still in it
     * and the row was not actually clickable. {@code format} substitutes into the pattern before
     * it is parsed, which fills both positions.</p>
     *
     * <p>Every value here is server-derived — a world name and three integers — so interpolating
     * them as markup carries no player-controlled input.</p>
     */
    private @NotNull Component signListEntry(@NotNull String worldName,
                                             @NotNull RealtySignEntity signEntity) {
        return MiniMessage.miniMessage().deserialize(this.messages.format(MessageKeys.SIGN_LIST_ENTRY,
                "world", worldName,
                "x", String.valueOf(signEntity.blockX()),
                "y", String.valueOf(signEntity.blockY()),
                "z", String.valueOf(signEntity.blockZ())));
    }
}
