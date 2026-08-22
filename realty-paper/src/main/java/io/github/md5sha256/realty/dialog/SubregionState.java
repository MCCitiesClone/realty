package io.github.md5sha256.realty.dialog;

import io.github.md5sha256.realty.command.DurationUnit;
import com.sk89q.worldedit.regions.Region;
import net.kyori.adventure.text.Component;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Per-player wizard state tracked by {@link SubregionDialog} between its dialog pages. */
public final class SubregionState {
    public Region selection;
    public World world;
    public UUID worldId;
    public final List<String> parentCandidates = new ArrayList<>();
    public String parentId;
    public String name = "";
    public String price = "100";
    public String durationAmount = "30";
    public String durationUnit = DurationUnit.DAYS.name();
    public boolean unlimitedRenewals = true;
    public String maxRenewals = "3";
    public final List<String> permittedTagIds = new ArrayList<>();
    public final Set<String> selectedTags = new LinkedHashSet<>();
    // Shown at the top of the details dialog when validation fails, so the message isn't hidden
    // behind the reopened dialog. Cleared once rendered.
    public Component error;
}
