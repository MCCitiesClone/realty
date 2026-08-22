package io.github.md5sha256.realty.command.resolver;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a WorldGuard region by name, within the world the sender is in.
 *
 * <p>Region names are only unique per world, so the sender's world is what disambiguates them.
 * A non-player sender has no world of its own; it falls back to the primary world, which is the
 * same region set the command source stack resolved against previously.</p>
 *
 * <p>Commands take the region as an <em>optional</em> argument and fall back to
 * {@link #regionAt(Location)} when it is omitted, so a player standing in a region need not name
 * it. An omitted optional argument arrives as {@code null}, which is what makes that work.</p>
 */
public final class WorldGuardRegionResolver implements ParameterResolver<WorldGuardRegion> {

    @Override
    public @NotNull Class<WorldGuardRegion> type() {
        return WorldGuardRegion.class;
    }

    @Override
    public @NotNull Optional<WorldGuardRegion> resolve(@NotNull String token,
                                                       @NotNull CommandSender sender) {
        World world = worldOf(sender);
        RegionManager manager = managerFor(world);
        if (manager == null) {
            return Optional.empty();
        }
        ProtectedRegion region = manager.getRegion(token);
        return region == null ? Optional.empty() : Optional.of(new WorldGuardRegion(region, world));
    }

    @Override
    public @NotNull List<String> suggestions(@NotNull String prefix, @NotNull CommandSender sender) {
        RegionManager manager = managerFor(worldOf(sender));
        if (manager == null) {
            return List.of();
        }
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return manager.getRegions().keySet().stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(lowered))
                .sorted()
                .toList();
    }

    /**
     * The highest-priority region containing {@code location}, or {@code null} when the player is
     * standing outside every region. Used for the omitted-argument fallback.
     */
    public static @Nullable WorldGuardRegion regionAt(@NotNull Location location) {
        World world = location.getWorld();
        RegionManager manager = managerFor(world);
        if (manager == null) {
            return null;
        }
        BlockVector3 position =
                BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        ApplicableRegionSet applicable = manager.getApplicableRegions(position);
        return applicable.getRegions().stream()
                .max(Comparator.comparingInt(ProtectedRegion::getPriority))
                .map(best -> new WorldGuardRegion(best, world))
                .orElse(null);
    }

    /**
     * The region the command should act on: the one named, or failing that the one the player is
     * standing in.
     *
     * @param named the resolved {@code [region]} argument, or {@code null} when omitted
     */
    public static @Nullable WorldGuardRegion resolveOrStandingIn(@Nullable WorldGuardRegion named,
                                                                 @NotNull Player sender) {
        return named != null ? named : regionAt(sender.getLocation());
    }

    private static @NotNull World worldOf(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        return Bukkit.getWorlds().getFirst();
    }

    private static @Nullable RegionManager managerFor(@NotNull World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));
    }
}
