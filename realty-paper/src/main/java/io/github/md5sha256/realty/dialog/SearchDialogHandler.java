package io.github.md5sha256.realty.dialog;

import com.google.inject.Inject;
import io.github.md5sha256.realty.database.entity.OccupancyFilter;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.ConfigRegionTag;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.ButtonSpec;
import io.paradaux.hibernia.framework.usher.DialogFlow;
import io.paradaux.hibernia.framework.usher.DialogView;
import io.paradaux.hibernia.framework.usher.Text;
import io.paradaux.hibernia.framework.usher.annotations.Action;
import io.paradaux.hibernia.framework.usher.annotations.ActionArg;
import io.paradaux.hibernia.framework.usher.annotations.Dialog;
import io.paradaux.hibernia.framework.usher.annotations.Input;
import io.paradaux.hibernia.framework.usher.annotations.Model;
import io.paradaux.hibernia.framework.usher.annotations.Screen;
import io.paradaux.hibernia.framework.usher.input.DialogInputSpec;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The region search dialog: criteria on one screen, a tag filter on another.
 *
 * <p>The tag screen is why this needed {@code @ActionArg}. Its buttons are one per tag configured
 * in {@code region-tags.yml} and visible to the player, so the set is not known until the screen
 * renders; every button shares one action and is told apart by the tag id it carries.</p>
 */
@Dialog("realty-search")
public final class SearchDialogHandler implements DialogHandler {

    private static final int MAX_TAGS_PER_COLUMN = 10;

    static final String INPUT_FREEHOLD = "freehold";
    static final String INPUT_LEASEHOLD = "leasehold";
    static final String INPUT_MIN_PRICE = "min_price";
    static final String INPUT_MAX_PRICE = "max_price";
    static final String INPUT_OCCUPANCY = "occupancy";

    private final AtomicReference<RealtyTags> realtyTags;
    private final SearchResults search;
    private final Message messages;

    @Inject
    public SearchDialogHandler(@NotNull AtomicReference<RealtyTags> realtyTags,
                               @NotNull SearchResults search,
                               @NotNull Message messages) {
        this.realtyTags = realtyTags;
        this.search = search;
        this.messages = messages;
    }

    /** Seeds a model with the tags this player may filter by. */
    public @NotNull SearchModel newModel(@NotNull Player player) {
        SearchModel model = new SearchModel();
        for (ConfigRegionTag tag : this.realtyTags.get().values()) {
            if (tag.permission() == null || player.hasPermission(tag.permission().node())) {
                model.tagStates().put(tag.tagId(), SearchModel.TagState.IGNORE);
            }
        }
        return model;
    }

    @Screen("main")
    public @NotNull DialogView main(@Model SearchModel model) {
        DialogView.Builder view = DialogView.multiAction(Text.of(Component.text("Search Regions")))
                .body(Text.of(Component.text("Filter regions by type and price range.")))
                .bool(INPUT_FREEHOLD, Text.of(Component.text("Freehold")), model.freehold())
                .bool(INPUT_LEASEHOLD, Text.of(Component.text("Leasehold")), model.leasehold())
                .text(INPUT_MIN_PRICE, Text.of(Component.text("Min Price")))
                .text(INPUT_MAX_PRICE, Text.of(Component.text("Max Price")))
                .option(INPUT_OCCUPANCY, Text.of(Component.text("Occupancy")), List.of(
                        new DialogInputSpec.OptionSpec(OccupancyFilter.IGNORE.name(),
                                Text.of(Component.text("Any")),
                                model.occupancy() == OccupancyFilter.IGNORE),
                        new DialogInputSpec.OptionSpec(OccupancyFilter.OCCUPIED.name(),
                                Text.of(Component.text("Occupied")),
                                model.occupancy() == OccupancyFilter.OCCUPIED),
                        new DialogInputSpec.OptionSpec(OccupancyFilter.UNOCCUPIED.name(),
                                Text.of(Component.text("Unoccupied")),
                                model.occupancy() == OccupancyFilter.UNOCCUPIED)))
                .button(ButtonSpec.action(
                        Text.of(Component.text("Search", NamedTextColor.GREEN)), "search")
                        .withWidth(150));
        // The tag screen is only reachable when this player can filter by at least one tag.
        if (!model.tagStates().isEmpty()) {
            view.button(ButtonSpec.action(Text.of(Component.text("Filter Tags")), "openTags")
                    .withWidth(150));
        }
        return view.exit(ButtonSpec.close(Text.of(Component.text("Cancel"))).withWidth(150))
                .columns(model.tagStates().isEmpty() ? 1 : 2)
                .build();
    }

    @Screen("tags")
    public @NotNull DialogView tags(@Model SearchModel model) {
        DialogView.Builder view = DialogView.multiAction(Text.of(Component.text("Filter Tags")))
                .body(Text.of(Component.text("Click a tag to cycle: Ignore -> Include -> Exclude")));

        RealtyTags tags = this.realtyTags.get();
        int count = 0;
        for (String tagId : model.tagStates().keySet()) {
            ConfigRegionTag tag = tags.get(tagId);
            if (tag == null) {
                // The tag was removed from region-tags.yml while the dialog was open.
                continue;
            }
            view.button(ButtonSpec.action(
                    Text.of(label(tag.tagDisplayName(), model.tagState(tagId))), "cycleTag", tagId)
                    .withWidth(150));
            count++;
        }
        return view.exit(ButtonSpec.back(Text.of(Component.text("Done"))).withWidth(150))
                .columns(Math.max(1, (count + MAX_TAGS_PER_COLUMN - 1) / MAX_TAGS_PER_COLUMN))
                .build();
    }

    @Action("openTags")
    public void openTags(@Model SearchModel model, DialogFlow flow,
                         @Input(INPUT_FREEHOLD) boolean freehold,
                         @Input(INPUT_LEASEHOLD) boolean leasehold,
                         @Input(INPUT_MIN_PRICE) String minPrice,
                         @Input(INPUT_MAX_PRICE) String maxPrice,
                         @Input(INPUT_OCCUPANCY) OccupancyFilter occupancy) {
        // Remember before navigating: a navigation button does not read the inputs, so leaving
        // this out would silently discard whatever the player typed.
        remember(model, freehold, leasehold, minPrice, maxPrice, occupancy);
        flow.open("tags");
    }

    @Action("cycleTag")
    public void cycleTag(@ActionArg String tagId, @Model SearchModel model, DialogFlow flow) {
        model.cycleTag(tagId);
        flow.refresh();
    }

    @Action("search")
    public void search(@Model SearchModel model, DialogFlow flow, Player viewer,
                       @Input(INPUT_FREEHOLD) boolean freehold,
                       @Input(INPUT_LEASEHOLD) boolean leasehold,
                       @Input(INPUT_MIN_PRICE) String minPrice,
                       @Input(INPUT_MAX_PRICE) String maxPrice,
                       @Input(INPUT_OCCUPANCY) OccupancyFilter occupancy) {
        remember(model, freehold, leasehold, minPrice, maxPrice, occupancy);

        List<String> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (Map.Entry<String, SearchModel.TagState> entry : model.tagStates().entrySet()) {
            if (entry.getValue() == SearchModel.TagState.INCLUDE) {
                included.add(entry.getKey());
            } else if (entry.getValue() == SearchModel.TagState.EXCLUDE) {
                excluded.add(entry.getKey());
            }
        }
        Collection<String> tagFilter = included.isEmpty() ? null : included;
        Collection<String> excludeFilter = excluded.isEmpty() ? null : excluded;

        flow.close();
        if (!model.freehold() && !model.leasehold()) {
            viewer.sendMessage(this.messages.component(MessageKeys.SEARCH_NO_RESULTS));
            return;
        }
        this.search.performSearch(viewer, model.freehold(), model.leasehold(), tagFilter,
                excludeFilter, SearchResults.parsePrice(model.minPrice(), 0.0),
                SearchResults.parsePrice(model.maxPrice(), Double.MAX_VALUE),
                model.occupancy(), 1);
    }

    private static void remember(SearchModel model, boolean freehold, boolean leasehold,
                                 String minPrice, String maxPrice, OccupancyFilter occupancy) {
        model.remember(freehold, leasehold,
                minPrice == null ? "" : minPrice,
                maxPrice == null ? "" : maxPrice,
                occupancy == null ? OccupancyFilter.UNOCCUPIED : occupancy);
    }

    private static @NotNull Component label(@NotNull Component tagName,
                                            @NotNull SearchModel.TagState state) {
        return switch (state) {
            case IGNORE -> tagName.colorIfAbsent(NamedTextColor.GRAY);
            case INCLUDE -> Component.text().color(NamedTextColor.GREEN)
                    .append(Component.text("[Include] ")).append(tagName).build();
            case EXCLUDE -> Component.text().color(NamedTextColor.RED)
                    .append(Component.text("[Exclude] ")).append(tagName).build();
        };
    }
}
