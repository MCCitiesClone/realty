package io.github.md5sha256.realty.dialog;

import com.google.inject.Inject;
import com.sk89q.worldguard.protection.managers.RegionManager;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.command.DurationUnit;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.ConfigRegionTag;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.github.md5sha256.realty.wand.SubregionWandManager;
import io.github.md5sha256.realty.wand.WandSelection;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.ButtonSpec;
import io.paradaux.hibernia.framework.usher.DialogContext;
import io.paradaux.hibernia.framework.usher.DialogFlow;
import io.paradaux.hibernia.framework.usher.DialogView;
import io.paradaux.hibernia.framework.usher.Text;
import io.paradaux.hibernia.framework.usher.annotations.Action;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The three-step subregion creation flow: height, then details (with an optional tag screen), then
 * confirmation.
 *
 * <p>Only the screens live here. Validating the selection, finding its candidate parents and
 * writing the region belong to {@link SubregionFlow}, which prepares the model this is opened
 * with.</p>
 */
@Dialog("realty-subregion")
public final class SubregionDialogHandler implements DialogHandler {

    private static final int FORM_INPUT_WIDTH = 200;
    private static final int DEFAULT_HEIGHT = 8;

    static final String INPUT_PARENT = "parent";
    static final String INPUT_NAME = "name";
    static final String INPUT_PRICE = "price";
    static final String INPUT_DURATION_AMOUNT = "duration_amount";
    static final String INPUT_DURATION_UNIT = "duration_unit";
    static final String INPUT_UNLIMITED_RENEWALS = "unlimited_renewals";
    static final String INPUT_MAX_RENEWALS = "max_renewals";
    static final String INPUT_HEIGHT = "height";
    static final String TAG_INPUT_PREFIX = "tag_";

    private final SubregionFlow flow;
    private final SubregionWandManager wandManager;
    private final AtomicReference<RealtyTags> realtyTags;
    private final Message messages;

    @Inject
    public SubregionDialogHandler(@NotNull SubregionFlow flow,
                                  @NotNull SubregionWandManager wandManager,
                                  @NotNull AtomicReference<RealtyTags> realtyTags,
                                  @NotNull Message messages) {
        this.flow = flow;
        this.wandManager = wandManager;
        this.realtyTags = realtyTags;
        this.messages = messages;
    }

    // ── step 1: height ────────────────────────────────────────────────────────────

    @Screen("height")
    public @NotNull DialogView height(Player viewer) {
        WandSelection selection = this.wandManager.get(viewer.getUniqueId());
        int baseFloor = selection.minPointY();
        int worldMax = selection.world().getMaxHeight() - 1;
        int maxHeight = Math.max(1, worldMax - baseFloor + 1);
        int current = selection.heightSet()
                ? selection.ceilingY() - selection.floorY() + 1
                : DEFAULT_HEIGHT;
        current = Math.max(1, Math.min(maxHeight, current));

        return DialogView.multiAction(Text.of(Component.text("Step 1 of 3: Height")))
                .body(Text.of(Component.text(
                        "Starts at the corners you placed. Drag to set the height.")))
                .number(INPUT_HEIGHT, Text.of(Component.text("Height")),
                        1f, (float) maxHeight, 1f, (float) current)
                .button(ButtonSpec.action(Text.of(Component.text("Preview")), "previewHeight")
                        .withWidth(150))
                .button(ButtonSpec.action(
                        Text.of(Component.text("Continue", NamedTextColor.GREEN)), "continueHeight")
                        .withWidth(150))
                .button(ButtonSpec.action(
                        Text.of(Component.text("Clear", NamedTextColor.RED)), "clearSelection")
                        .withWidth(150))
                .exit(ButtonSpec.close(Text.of(Component.text("Cancel"))).withWidth(150))
                .columns(1)
                .build();
    }

    @Action("previewHeight")
    public void previewHeight(Player viewer, DialogFlow flow,
                              @Input(INPUT_HEIGHT) float height) {
        saveHeight(viewer, height);
        // The dialog closes and the wand's particle outline previews the full shape.
        flow.close();
    }

    @Action("continueHeight")
    public void continueHeight(Player viewer, DialogFlow flow,
                               @Input(INPUT_HEIGHT) float height) {
        saveHeight(viewer, height);
        flow.close();
        // Re-enters through the flow so the selection is validated and its parents resolved.
        this.flow.open(viewer);
    }

    @Action("clearSelection")
    public void clearSelection(Player viewer, DialogFlow flow) {
        this.wandManager.clear(viewer.getUniqueId());
        viewer.sendMessage(this.messages.component(MessageKeys.SUBREGION_SELECTION_CLEARED));
        flow.close();
    }

    private void saveHeight(@NotNull Player viewer, float raw) {
        WandSelection selection = this.wandManager.get(viewer.getUniqueId());
        if (selection == null) {
            return;
        }
        int baseFloor = selection.minPointY();
        int worldMax = selection.world().getMaxHeight() - 1;
        int height = Math.max(1, Math.round(raw));
        selection.setHeight(baseFloor, Math.min(worldMax, baseFloor + height - 1));
    }

    // ── step 2: details ───────────────────────────────────────────────────────────

    @Screen("details")
    public @NotNull DialogView details(@Model SubregionState state, Player viewer) {
        List<DialogInputSpec.OptionSpec> parents = new ArrayList<>();
        for (String id : state.parentCandidates) {
            parents.add(new DialogInputSpec.OptionSpec(
                    id, Text.of(Component.text(id)), id.equals(state.parentId)));
        }
        List<DialogInputSpec.OptionSpec> units = new ArrayList<>();
        for (DurationUnit unit : DurationUnit.values()) {
            units.add(new DialogInputSpec.OptionSpec(unit.name(), Text.of(Component.text(unit.label)),
                    unit.name().equals(state.durationUnit)));
        }

        DialogView.Builder view = DialogView.multiAction(
                Text.of(Component.text("Step 2 of 3: Details")));
        if (state.error != null) {
            view.body(Text.of(state.error.colorIfAbsent(NamedTextColor.RED)));
        }
        // One-shot: the error belongs to this render, not to later Back/Done navigation.
        state.error = null;
        view.body(Text.of(Component.text("Landlord: " + viewer.getName())))
                .option(INPUT_PARENT, Text.of(Component.text("Region")), parents)
                .text(INPUT_NAME, Text.of(Component.text("Name")))
                .text(INPUT_PRICE, Text.of(Component.text("Price")))
                .text(INPUT_DURATION_AMOUNT, Text.of(Component.text("Lease length")))
                .option(INPUT_DURATION_UNIT, Text.of(Component.text("Unit")), units)
                .bool(INPUT_UNLIMITED_RENEWALS, Text.of(Component.text("Unlimited renewals")),
                        state.unlimitedRenewals)
                .text(INPUT_MAX_RENEWALS, Text.of(Component.text("Max renewals (if limited)")))
                .button(ButtonSpec.action(
                        Text.of(Component.text("Next", NamedTextColor.GREEN)), "detailsNext")
                        .withWidth(150));
        if (!state.permittedTagIds.isEmpty()) {
            view.button(ButtonSpec.action(Text.of(Component.text("Tags")), "openTags")
                    .withWidth(150));
        }
        return view.exit(ButtonSpec.close(Text.of(Component.text("Cancel"))).withWidth(150))
                .columns(1)
                .build();
    }

    @Action("detailsNext")
    public void detailsNext(@Model SubregionState state, DialogFlow flow, DialogContext ctx) {
        saveDetails(state, ctx);
        RegionManager regionManager = this.flow.regionManagerFor(state);
        state.error = regionManager == null
                ? this.flow.error("Region manager unavailable")
                : this.flow.validate(state, regionManager);
        // Either way the details screen re-renders: with the error, or replaced by the summary.
        if (state.error != null) {
            flow.refresh();
            return;
        }
        flow.open("confirm");
    }

    @Action("openTags")
    public void openTags(@Model SubregionState state, DialogFlow flow, DialogContext ctx) {
        saveDetails(state, ctx);
        flow.open("tags");
    }

    /** Reads the details screen's inputs into the model before navigating away from it. */
    private void saveDetails(@NotNull SubregionState state, @NotNull DialogContext ctx) {
        String parent = ctx.view().getText(INPUT_PARENT);
        if (parent != null && !parent.isBlank()) {
            state.parentId = parent;
        }
        state.name = orEmpty(ctx.view().getText(INPUT_NAME));
        state.price = orEmpty(ctx.view().getText(INPUT_PRICE));
        state.durationAmount = orEmpty(ctx.view().getText(INPUT_DURATION_AMOUNT));
        String unit = ctx.view().getText(INPUT_DURATION_UNIT);
        if (unit != null && !unit.isBlank()) {
            state.durationUnit = unit;
        }
        Boolean unlimited = ctx.view().getBoolean(INPUT_UNLIMITED_RENEWALS);
        state.unlimitedRenewals = unlimited == null || unlimited;
        state.maxRenewals = orEmpty(ctx.view().getText(INPUT_MAX_RENEWALS));
    }

    // ── step 2b: tags ─────────────────────────────────────────────────────────────

    @Screen("tags")
    public @NotNull DialogView tags(@Model SubregionState state) {
        DialogView.Builder view = DialogView.multiAction(
                        Text.of(Component.text("Step 2 of 3: Tags")))
                .body(Text.of(Component.text("Pick the tags for this subregion.")));
        RealtyTags tags = this.realtyTags.get();
        for (int i = 0; i < state.permittedTagIds.size(); i++) {
            String tagId = state.permittedTagIds.get(i);
            ConfigRegionTag tag = tags.get(tagId);
            if (tag == null) {
                continue;
            }
            // Keyed by index rather than id: a tag id may contain characters an input key cannot.
            view.bool(TAG_INPUT_PREFIX + i, Text.of(tag.tagDisplayName()),
                    state.selectedTags.contains(tagId));
        }
        return view.button(ButtonSpec.action(
                        Text.of(Component.text("Done", NamedTextColor.GREEN)), "tagsDone")
                        .withWidth(150))
                .exit(ButtonSpec.close(Text.of(Component.text("Cancel"))).withWidth(150))
                .columns(1)
                .build();
    }

    @Action("tagsDone")
    public void tagsDone(@Model SubregionState state, DialogFlow flow, DialogContext ctx) {
        // The inputs are keyed by index, so no @Input annotation can name them; the raw view can.
        state.selectedTags.clear();
        for (int i = 0; i < state.permittedTagIds.size(); i++) {
            Boolean selected = ctx.view().getBoolean(TAG_INPUT_PREFIX + i);
            if (selected != null && selected) {
                state.selectedTags.add(state.permittedTagIds.get(i));
            }
        }
        flow.back();
    }

    // ── step 3: confirm ───────────────────────────────────────────────────────────

    @Screen("confirm")
    public @NotNull DialogView confirm(@Model SubregionState state, Player viewer) {
        double price = SubregionFlow.parsePrice(state.price);
        String renewals = state.unlimitedRenewals ? "Unlimited" : state.maxRenewals;

        DialogView.Builder view = DialogView.multiAction(
                        Text.of(Component.text("Step 3 of 3: Confirm")))
                .body(Text.of(Component.text()
                        .append(Component.text("Renting out "))
                        .append(Component.text(state.name, NamedTextColor.AQUA))
                        .build()))
                .body(Text.of(Component.text("Landlord: " + viewer.getName())))
                .body(Text.of(Component.text()
                        .append(Component.text("Price: "))
                        .append(Component.text(CurrencyFormatter.format(price), NamedTextColor.GREEN))
                        .build()))
                .body(Text.of(Component.text()
                        .append(Component.text("Lease: "))
                        .append(Component.text(SubregionFlow.durationSummary(state),
                                NamedTextColor.GREEN))
                        .build()))
                .body(Text.of(Component.text("Renewals: " + renewals)));
        if (!state.selectedTags.isEmpty()) {
            view.body(Text.of(Component.text(
                    "Tags: " + String.join(", ", state.selectedTags))));
        }
        return view.button(ButtonSpec.action(
                        Text.of(Component.text("Confirm", NamedTextColor.GREEN)), "submit")
                        .withWidth(150))
                .button(ButtonSpec.back(Text.of(Component.text("Back"))).withWidth(150))
                .exit(ButtonSpec.close(Text.of(Component.text("Cancel"))).withWidth(150))
                .columns(1)
                .build();
    }

    @Action("submit")
    public void submit(@Model SubregionState state, Player viewer, DialogFlow flow) {
        flow.close();
        this.flow.submit(viewer, state);
    }

    private static @NotNull String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
