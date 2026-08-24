package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.dialog.SearchDialogHandler;
import io.github.md5sha256.realty.dialog.SearchResults;
import com.google.inject.Provider;
import io.paradaux.hibernia.framework.usher.DialogManager;
import io.github.md5sha256.realty.database.entity.OccupancyFilter;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /realty search …} family: the search dialog and the paginated results it links to.
 *
 * <p>The results route is what the dialog's own buttons run, so its flags mirror the dialog's
 * inputs.</p>
 */
@Command({"realty", "rl"})
public final class SearchCommands implements CommandHandler {

    private final SearchResults searchResults;
    private final SearchDialogHandler dialogHandler;
    // A Provider for the same reason as SubregionFlow: DialogManager injects the set of dialog
    // handlers, so holding it directly puts this class one edge away from an unproxyable cycle the
    // moment any handler needs something that reaches back here.
    private final Provider<DialogManager> dialogs;
    private final Message messages;

    @Inject
    public SearchCommands(@NotNull SearchResults searchResults,
                          @NotNull SearchDialogHandler dialogHandler,
                          @NotNull Provider<DialogManager> dialogs,
                          @NotNull Message messages) {
        this.searchResults = searchResults;
        this.dialogHandler = dialogHandler;
        this.dialogs = dialogs;
        this.messages = messages;
    }

    @Route("search")
    @Permission("realty.command.search")
    @Description("Open the region search dialog")
    public void open(@Sender CommandSender sender) {

                if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        this.dialogs.get().open(player, SearchDialogHandler.class, this.dialogHandler.newModel(player));
    }

    @Route("search results")
    @Permission("realty.command.search")
    @Description("Show search results")
    public void results(@Sender CommandSender sender,
                        @Flag(value = "freehold", presence = true) boolean freeholdFlag,
                        @Flag(value = "leasehold", presence = true) boolean leaseholdFlag,
                        @Flag("tags") @Nullable String tags,
                        @Flag("exclude-tags") @Nullable String excludeTags,
                        @Flag(value = "min-price", defaultValue = "0", min = 0) double minPrice,
                        @Flag(value = "max-price", min = 0) @Nullable Double maxPriceFlag,
                        @Flag(value = "occupancy", defaultValue = "UNOCCUPIED") OccupancyFilter occupancy,
                        @Flag(value = "page", defaultValue = "1", min = 1) int page) {

                boolean includeFreehold = freeholdFlag;
        boolean includeLeasehold = leaseholdFlag;
        if (!includeFreehold && !includeLeasehold) {
            includeFreehold = true;
            includeLeasehold = true;
        }
        Collection<String> tagIds = parseTagIds(tags);
        Collection<String> excludedTagIds = parseTagIds(excludeTags);
        // An absent --max-price means no upper bound.
        double maxPrice = maxPriceFlag != null ? maxPriceFlag : Double.MAX_VALUE;
        this.searchResults.performSearch(sender, includeFreehold, includeLeasehold, tagIds,
                excludedTagIds, minPrice, maxPrice, occupancy, page);
    }

    @Nullable
    private static Collection<String> parseTagIds(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? null : result;
    }
}
