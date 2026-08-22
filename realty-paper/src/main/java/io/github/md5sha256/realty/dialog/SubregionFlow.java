package io.github.md5sha256.realty.dialog;

import io.paradaux.hibernia.framework.usher.DialogManager;
import io.github.md5sha256.realty.command.util.SubregionLandlordUpdater;
import io.github.md5sha256.realty.command.DurationUnit;
import com.google.inject.Inject;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.util.SubregionSelectionValidator;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.entity.FreeholdContractEntity;
import io.github.md5sha256.realty.database.mapper.RegionTagMapper;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.ConfigRegionTag;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.github.md5sha256.realty.settings.Settings;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.github.md5sha256.realty.wand.SubregionWandManager;
import io.github.md5sha256.realty.wand.WandSelection;

/**
 * Guided two-page dialog for creating a subregion from the player's wand selection.
 *
 * <p>Page 1 collects the parent freehold (auto-detected from the selection), name, price and
 * duration; page 2 is a plain-English confirmation. Submit calls
 * {@link RealtyPaperApi#quickCreateSubregion}. Modelled on {@code SearchDialog}.</p>
 */
public final class SubregionFlow {

    static final String BYPASS_PERMISSION = "realty.command.subregion.confirm.bypass";
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9-]+$");

    private static final String INPUT_PARENT = "parent";
    private static final String INPUT_NAME = "name";
    private static final String INPUT_PRICE = "price";
    private static final String INPUT_DURATION_AMOUNT = "duration_amount";
    private static final String INPUT_DURATION_UNIT = "duration_unit";
    private static final String INPUT_UNLIMITED_RENEWALS = "unlimited_renewals";
    private static final String INPUT_MAX_RENEWALS = "max_renewals";
    private static final String INPUT_HEIGHT = "height";
    private static final String TAG_INPUT_PREFIX = "tag_";
    private static final int DEFAULT_HEIGHT = 16;
    private static final int FORM_INPUT_WIDTH = 200;
    /** Stored as the lease's max renewals to mean "no cap"; the backend maps any negative to NULL. */
    private static final int UNLIMITED_RENEWALS = -1;
    private static final ClickCallback.Options CLICK_OPTIONS =
            ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build();

    private final RealtyPaperApi api;
    private final ExecutorState executorState;
    private final Database database;
    private final SubregionWandManager wandManager;
    private final AtomicReference<Settings> settings;
    private final AtomicReference<RealtyTags> realtyTags;
    private final Message messages;
    private final DialogManager dialogs;

    @Inject
    public SubregionFlow(@NotNull RealtyPaperApi api,
                           @NotNull ExecutorState executorState,
                           @NotNull Database database,
                           @NotNull SubregionWandManager wandManager,
                           @NotNull AtomicReference<Settings> settings,
                           @NotNull AtomicReference<RealtyTags> realtyTags,
                           @NotNull Message messages,
                           @NotNull DialogManager dialogs) {
        this.api = api;
        this.executorState = executorState;
        this.database = database;
        this.wandManager = wandManager;
        this.settings = settings;
        this.realtyTags = realtyTags;
        this.messages = messages;
        this.dialogs = dialogs;
    }

    /**
     * Opens the creation dialog for the player, auto-detecting candidate parent freeholds from the
     * current wand selection.
     */
    public void open(@NotNull Player player) {
        WandSelection wandSelection = wandManager.get(player.getUniqueId());
        if (wandSelection == null || !wandSelection.isComplete()) {
            player.sendMessage(messages.component(MessageKeys.SUBREGION_SELECTION_INCOMPLETE));
            return;
        }
        if (!wandSelection.heightSet()) {
            // No vertical span chosen yet — collect it first, then this dialog reopens.
            openHeight(player);
            return;
        }
        Region selection = wandSelection.toRegion();
        World world = wandSelection.world();

        int minVolume = settings.get().subregionMinVolume();
        if (selection.getVolume() < minVolume) {
            player.sendMessage(messages.component(MessageKeys.SUBREGION_TOO_SMALL,
                    "volume", String.valueOf(selection.getVolume()),
                    "min-volume", String.valueOf(minVolume)));
            return;
        }

        RegionManager regionManager = regionManager(world);
        if (regionManager == null) {
            player.sendMessage(messages.component(MessageKeys.COMMON_ERROR,
                    "error", "Region manager unavailable"));
            return;
        }

        boolean canBypass = player.hasPermission(BYPASS_PERMISSION);
        SubregionSelectionValidator.ParentSearch search = SubregionSelectionValidator.candidateParents(
                player.getUniqueId(), canBypass, selection, regionManager);
        List<ProtectedRegion> geometricCandidates = search.candidates();
        if (geometricCandidates.isEmpty()) {
            // Distinguish "selection sits outside every region" from "you don't own the region it's in".
            String key = search.anyContaining()
                    ? MessageKeys.SUBREGION_NO_PARENT_CANDIDATES
                    : MessageKeys.SUBREGION_PARENT_OUTSIDE;
            player.sendMessage(messages.component(key));
            return;
        }

        UUID worldId = world.getUID();
        Collection<String> blacklist = settings.get().subregionTagBlacklist();

        // Filter to actual freeholds that aren't tag-blacklisted, without blocking a worker thread.
        List<CompletableFuture<CandidateCheck>> checks = new ArrayList<>();
        for (ProtectedRegion candidate : geometricCandidates) {
            String id = candidate.getId();
            CompletableFuture<FreeholdContractEntity> freehold = api.getFreeholdContract(id, worldId);
            CompletableFuture<List<String>> tags = blacklist.isEmpty()
                    ? CompletableFuture.completedFuture(List.of())
                    : api.getTagIdsByRegion(id);
            checks.add(freehold.thenCombine(tags, (contract, tagList) -> {
                boolean isFreehold = contract != null;
                boolean blacklisted = false;
                for (String tag : tagList) {
                    if (blacklist.contains(tag)) {
                        blacklisted = true;
                        break;
                    }
                }
                return new CandidateCheck(id, isFreehold, blacklisted);
            }));
        }

        CompletableFuture.allOf(checks.toArray(new CompletableFuture[0]))
                .thenAcceptAsync(ignored -> {
                    SubregionState state = new SubregionState();
                    state.selection = selection;
                    state.world = world;
                    state.worldId = worldId;
                    boolean anyFreehold = false;
                    for (CompletableFuture<CandidateCheck> check : checks) {
                        CandidateCheck result = check.join();
                        if (!result.isFreehold()) {
                            continue;
                        }
                        anyFreehold = true;
                        if (!result.blacklisted()) {
                            state.parentCandidates.add(result.id());
                        }
                    }
                    if (state.parentCandidates.isEmpty()) {
                        // anyFreehold means the only thing stopping us is the tag blacklist;
                        // otherwise the owned region simply isn't a freehold parent.
                        player.sendMessage(messages.component(anyFreehold
                                ? MessageKeys.SUBREGION_TAG_BLACKLISTED
                                : MessageKeys.SUBREGION_PARENT_NOT_FREEHOLD));
                        return;
                    }
                    state.parentId = state.parentCandidates.get(0);
                    for (ConfigRegionTag tag : realtyTags.get().values()) {
                        if (tag.permission() == null
                                || player.hasPermission(tag.permission().node())) {
                            state.permittedTagIds.add(tag.tagId());
                        }
                    }
                    this.dialogs.open(player, SubregionDialogHandler.class, "details", state);
                }, executorState.mainThreadExec())
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    player.sendMessage(messages.component(MessageKeys.SUBREGION_CREATE_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    }

    /**
     * Opens the height dialog for the player's current footprint selection.
     */
    public void openHeight(@NotNull Player player) {
        WandSelection selection = this.wandManager.get(player.getUniqueId());
        if (selection == null || !selection.isComplete()) {
            player.sendMessage(this.messages.component(MessageKeys.SUBREGION_SELECTION_INCOMPLETE));
            return;
        }
        SubregionState state = new SubregionState();
        state.world = selection.world();
        state.worldId = state.world.getUID();
        this.dialogs.open(player, SubregionDialogHandler.class, "height", state);
    }

    void submit(@NotNull Player player, @NotNull SubregionState state) {
        RegionManager regionManager = regionManager(state.world);
        state.error = regionManager == null
                ? error("Region manager unavailable")
                : validate(state, regionManager);
        if (state.error != null) {
            // Reopen at the details screen so the error is visible rather than hidden behind it.
            this.dialogs.open(player, SubregionDialogHandler.class, "details", state);
            return;
        }
        ProtectedRegion parent = regionManager.getRegion(state.parentId);
        if (parent == null) {
            state.error = messages.component(MessageKeys.SUBREGION_NO_FREEHOLD,
                    "region", state.parentId);
            this.dialogs.open(player, SubregionDialogHandler.class, "details", state);
            return;
        }
        WorldGuardRegion parentRegion = new WorldGuardRegion(parent, state.world);
        double price = parsePrice(state.price);
        long durationSeconds = resolveDuration(state).toSeconds();
        int maxRenewals = resolveMaxRenewals(state);
        String name = state.name;

        api.quickCreateSubregion(parentRegion, name, state.selection, price, durationSeconds,
                        maxRenewals, player.getUniqueId())
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyPaperApi.QuickCreateSubregionResult.Success s -> {
                            wandManager.clear(player.getUniqueId());
                            applyTags(s.regionId(), new LinkedHashSet<>(state.selectedTags));
                            player.sendMessage(messages.component(MessageKeys.SUBREGION_CREATE_SUCCESS,
                                    "region", s.regionId(),
                                    "parent", s.parentId()));
                        }
                        case RealtyPaperApi.QuickCreateSubregionResult.NoFreeholdContract nfc ->
                                player.sendMessage(messages.component(MessageKeys.SUBREGION_NO_FREEHOLD,
                                        "region", nfc.parentId()));
                        case RealtyPaperApi.QuickCreateSubregionResult.RegionExists re ->
                                player.sendMessage(messages.component(MessageKeys.SUBREGION_REGION_EXISTS,
                                        "region", re.regionId()));
                        case RealtyPaperApi.QuickCreateSubregionResult.Error error ->
                                player.sendMessage(messages.component(MessageKeys.SUBREGION_CREATE_ERROR,
                                        "error", error.message()));
                    }
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    player.sendMessage(messages.component(MessageKeys.SUBREGION_CREATE_ERROR,
                            "error", String.valueOf(cause.getMessage())));
                    return null;
                });
    }

    private void applyTags(@NotNull String regionId, @NotNull Set<String> tagIds) {
        if (tagIds.isEmpty()) {
            return;
        }
        executorState.dbExec().execute(() -> {
            try (SqlSessionWrapper session = database.openSession(true)) {
                RegionTagMapper mapper = session.regionTagMapper();
                for (String tagId : tagIds) {
                    mapper.insertIfAbsent(tagId, regionId);
                }
            }
        });
    }

    /**
     * Validates the current form. Returns {@code null} when everything is valid, otherwise the
     * error message to show at the top of the details dialog. Geometry/ownership were already
     * enforced at {@link #open}; this re-checks the user-entered fields plus name uniqueness and
     * sibling overlap against the chosen parent.
     */
    @Nullable Component validate(@NotNull SubregionState state,
                                         @NotNull RegionManager regionManager) {
        if (state.name == null || !VALID_NAME_PATTERN.matcher(state.name).matches()) {
            return messages.component(MessageKeys.SUBREGION_INVALID_NAME,
                    "region", String.valueOf(state.name));
        }
        if (regionManager.getRegion(state.name) != null) {
            return messages.component(MessageKeys.SUBREGION_REGION_EXISTS,
                    "region", state.name);
        }
        ProtectedRegion parent = regionManager.getRegion(state.parentId);
        if (parent == null) {
            return messages.component(MessageKeys.SUBREGION_NO_FREEHOLD,
                    "region", String.valueOf(state.parentId));
        }
        ProtectedRegion sibling = SubregionSelectionValidator.overlappingSibling(
                state.selection, parent, regionManager);
        if (sibling != null) {
            return messages.component(MessageKeys.SUBREGION_OVERLAPS_SIBLING,
                    "sibling", sibling.getId());
        }
        if (parsePrice(state.price) <= 0) {
            return error("Price must be more than 0.");
        }
        Duration duration = resolveDuration(state);
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return error("Lease length must be more than 0.");
        }
        if (resolveMaxRenewals(state) == null) {
            return error("Max renewals must be 0 or more.");
        }
        return null;
    }

    @NotNull Component error(@NotNull String text) {
        return messages.component(MessageKeys.COMMON_ERROR, "error", text);
    }

    /**
     * Returns the max-renewals value to store: {@code -1} for unlimited, otherwise the entered
     * count. Returns {@code null} if the entered count isn't a valid non-negative number.
     */
    Integer resolveMaxRenewals(@NotNull SubregionState state) {
        if (state.unlimitedRenewals) {
            return UNLIMITED_RENEWALS;
        }
        try {
            int value = Integer.parseInt(state.maxRenewals.trim());
            return value < 0 ? null : value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    Duration resolveDuration(@NotNull SubregionState state) {
        long amount;
        try {
            amount = Long.parseLong(state.durationAmount.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (amount <= 0) {
            return null;
        }
        return Duration.ofSeconds(amount * durationUnitOf(state).seconds);
    }

    static DurationUnit durationUnitOf(@NotNull SubregionState state) {
        try {
            return DurationUnit.valueOf(state.durationUnit);
        } catch (IllegalArgumentException ex) {
            return DurationUnit.DAYS;
        }
    }

    static String durationSummary(@NotNull SubregionState state) {
        return state.durationAmount + " "
                + durationUnitOf(state).label.toLowerCase(java.util.Locale.ROOT);
    }

    static double parsePrice(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** The region manager for a prepared state's world, or null when unavailable. */
    @Nullable RegionManager regionManagerFor(@NotNull SubregionState state) {
        return state.world == null ? null : regionManager(state.world);
    }

    static RegionManager regionManager(@NotNull World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));
    }

    /**
     * The result of database-filtering one geometric candidate parent: whether it is a registered
     * freehold contract and whether it carries a blacklisted tag. Keeping both flags lets the
     * dialog explain exactly why no parent was usable.
     */
    private record CandidateCheck(@NotNull String id, boolean isFreehold, boolean blacklisted) {
    }
}
