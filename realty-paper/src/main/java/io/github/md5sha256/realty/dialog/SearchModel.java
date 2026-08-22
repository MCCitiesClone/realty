package io.github.md5sha256.realty.dialog;

import io.github.md5sha256.realty.database.entity.OccupancyFilter;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The criteria a player is assembling in the search dialog, carried between its screens.
 *
 * <p>Replaces the {@code Map<UUID, SearchState>} the dialog used to keep: the flow owns one of
 * these per open dialog, so nothing has to be keyed by player or cleaned up when they log out
 * mid-search.</p>
 */
public final class SearchModel {

    /** What a tag contributes to the query. Clicking its button advances through these in order. */
    public enum TagState {
        IGNORE,
        INCLUDE,
        EXCLUDE;

        public @NotNull TagState next() {
            return switch (this) {
                case IGNORE -> INCLUDE;
                case INCLUDE -> EXCLUDE;
                case EXCLUDE -> IGNORE;
            };
        }
    }

    /** Only the tags this player may see; populated when the dialog opens. */
    private final Map<String, TagState> tagStates = new LinkedHashMap<>();

    private boolean freehold = true;
    private boolean leasehold = true;
    private String minPrice = "";
    private String maxPrice = "";
    private OccupancyFilter occupancy = OccupancyFilter.UNOCCUPIED;

    public @NotNull Map<String, TagState> tagStates() {
        return this.tagStates;
    }

    public void cycleTag(@NotNull String tagId) {
        this.tagStates.computeIfPresent(tagId, (id, state) -> state.next());
    }

    public @NotNull TagState tagState(@NotNull String tagId) {
        return this.tagStates.getOrDefault(tagId, TagState.IGNORE);
    }

    public boolean freehold() {
        return this.freehold;
    }

    public boolean leasehold() {
        return this.leasehold;
    }

    public @NotNull String minPrice() {
        return this.minPrice;
    }

    public @NotNull String maxPrice() {
        return this.maxPrice;
    }

    public @NotNull OccupancyFilter occupancy() {
        return this.occupancy;
    }

    /**
     * Records what the player typed before navigating away, so returning to the main screen shows
     * their criteria rather than the defaults.
     */
    public void remember(boolean freehold, boolean leasehold, @NotNull String minPrice,
                         @NotNull String maxPrice, @NotNull OccupancyFilter occupancy) {
        this.freehold = freehold;
        this.leasehold = leasehold;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.occupancy = occupancy;
    }
}
