package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.DurationFormatter;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.command.util.NamedAuthority;
import io.github.md5sha256.realty.database.entity.LeaseholdContractEntity;
import io.github.md5sha256.realty.database.entity.RealtyRegionEntity;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.party.PartyService;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /realty list …} family: the regions a party holds, rents or owns outright.
 */
@Command({"realty", "rl"})
public final class ListCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;
    private final PartyService parties;

    @Inject
    public ListCommands(@NotNull RealtyPaperApi api,
                        @NotNull Message messages,
                        @NotNull PartyService parties) {
        this.api = api;
        this.messages = messages;
        this.parties = parties;
    }

    @Route("list")
    @Permission("realty.command.list")
    @Description("List the regions you hold")
    public void list(@Sender CommandSender sender,
                     @Flag("player") @Nullable NamedAuthority player,
                     @Flag(value = "page", defaultValue = "1") int page) {
        show(sender, player, null, page);
    }

    @Route("list owned")
    @Permission("realty.command.list")
    @Description("List the freeholds you hold")
    public void listOwned(@Sender CommandSender sender,
                          @Flag("player") @Nullable NamedAuthority player,
                          @Flag(value = "page", defaultValue = "1") int page) {
        show(sender, player, "owned", page);
    }

    @Route("list rented")
    @Permission("realty.command.list")
    @Description("List the regions you rent")
    public void listRented(@Sender CommandSender sender,
                           @Flag("player") @Nullable NamedAuthority player,
                           @Flag(value = "page", defaultValue = "1") int page) {
        show(sender, player, "rented", page);
    }

    @Route("list me")
    @Permission("realty.command.list")
    @Description("List the regions you hold")
    public void listMe(@Sender CommandSender rawSender,
                       @Flag(value = "page", defaultValue = "1") int page) {
        // The proxy the Cloud tree spelled by injecting a --player flag pointing at the sender.
        // Stated directly here instead: /realty list me is /realty list --player <you>.
        if (!(rawSender instanceof Player player)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.LIST_PLAYERS_ONLY));
            return;
        }
        show(player, new NamedAuthority(player.getUniqueId(), player.getName()), null, page);
    }

    /**
     * Resolves whose regions to list -- the named party, or the sender when none was given -- and
     * renders the page.
     */
    private void show(@NotNull CommandSender sender, @Nullable NamedAuthority authority,
                      @Nullable String category, int page) {
        if (authority != null) {
            listRegions(sender, authority.uuid(), authority.name(), category, page);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.messages.component(MessageKeys.LIST_PLAYERS_ONLY));
            return;
        }
        listRegions(sender, player.getUniqueId(), player.getName(), category, page);
    }

    private static final int PAGE_SIZE = 10;

    private void resolvePlayer(@NotNull CommandSender sender, @NotNull NamedAuthority authority,
                               @Nullable String category, int page) {
        listRegions(sender, authority.uuid(), authority.name(), category, page);
    }

    private void listRegions(@NotNull CommandSender sender, @NotNull UUID targetId,
                             @NotNull String targetName, @Nullable String category, int page) {
        if (category == null) {
            listAll(sender, targetId, targetName, page);
        } else {
            listCategory(sender, targetId, targetName, category, page);
        }
    }

    private void listAll(@NotNull CommandSender sender, @NotNull UUID targetId,
                         @NotNull String targetName, int page) {
        int globalOffset = (page - 1) * PAGE_SIZE;
        this.api.listRegions(targetId, PAGE_SIZE, globalOffset).thenAccept(result -> {
            int totalCount = result.totalCount();
            if (totalCount == 0) {
                sender.sendMessage(this.messages.component(MessageKeys.LIST_NO_REGIONS,
                        "player", targetName));
                return;
            }

            int totalPages = (totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
            if (page > totalPages) {
                sender.sendMessage(this.messages.component(MessageKeys.LIST_INVALID_PAGE,
                        "page", String.valueOf(page),
                        "total", String.valueOf(totalPages)));
                return;
            }

            TextComponent.Builder builder = Component.text();
            builder.append(parseMiniMessage(MessageKeys.LIST_HEADER, "player", targetName));
            appendCategory(builder, "Owned", result.owned());
            appendCategory(builder, "Landlord", result.landlord());
            appendRentedCategory(builder, "Rented", result.rented());
            appendFooter(builder, targetName, null, page, totalPages);
            sender.sendMessage(builder.build());
        }).exceptionally(ex -> {
            sender.sendMessage(this.messages.component(MessageKeys.LIST_ERROR,
                    "error", ex.getMessage()));
            return null;
        });
    }

    private void listCategory(@NotNull CommandSender sender, @NotNull UUID targetId,
                              @NotNull String targetName, @NotNull String category, int page) {
        var future = "owned".equals(category)
                ? this.api.listOwnedRegions(targetId, PAGE_SIZE, (page - 1) * PAGE_SIZE)
                : this.api.listRentedRegions(targetId, PAGE_SIZE, (page - 1) * PAGE_SIZE);

        future.thenAccept(result -> {
            if (result.totalCount() == 0) {
                sender.sendMessage(this.messages.component(MessageKeys.LIST_NO_REGIONS,
                        "player", targetName));
                return;
            }

            int totalPages = (result.totalCount() + PAGE_SIZE - 1) / PAGE_SIZE;
            if (page > totalPages) {
                sender.sendMessage(this.messages.component(MessageKeys.LIST_INVALID_PAGE,
                        "page", String.valueOf(page),
                        "total", String.valueOf(totalPages)));
                return;
            }

            String label = "owned".equals(category) ? "Owned" : "Rented";
            TextComponent.Builder builder = Component.text();
            builder.append(parseMiniMessage(MessageKeys.LIST_HEADER, "player", targetName));
            if ("owned".equals(category)) {
                appendCategory(builder, label, result.regions());
            } else {
                appendRentedCategory(builder, label, result.regions());
            }
            appendFooter(builder, targetName, category, page, totalPages);
            sender.sendMessage(builder.build());
        }).exceptionally(ex -> {
            sender.sendMessage(this.messages.component(MessageKeys.LIST_ERROR,
                    "error", ex.getMessage()));
            return null;
        });
    }

    private void appendCategory(@NotNull TextComponent.Builder builder, @NotNull String label,
                                @NotNull List<RealtyRegionEntity> regions) {
        if (regions.isEmpty()) {
            return;
        }
        builder.appendNewline()
                .append(parseMiniMessage(MessageKeys.LIST_CATEGORY, "label", label));
        for (RealtyRegionEntity region : regions) {
            builder.appendNewline()
                    .append(parseMiniMessage(MessageKeys.LIST_ENTRY,
                            "region",
                            region.worldGuardRegionId()));
        }
    }

    /**
     * Appends rented regions with time-left info. Calls
     * {@link RealtyPaperApi#getLeaseholdContract} via {@code .join()} for each region.
     * This is safe because the callback runs on the db executor thread.
     */
    private void appendRentedCategory(@NotNull TextComponent.Builder builder, @NotNull String label,
                                      @NotNull List<RealtyRegionEntity> regions) {
        if (regions.isEmpty()) {
            return;
        }
        builder.appendNewline()
                .append(parseMiniMessage(MessageKeys.LIST_CATEGORY, "label", label));
        for (RealtyRegionEntity region : regions) {
            LeaseholdContractEntity leasehold = this.api.getLeaseholdContract(
                    region.worldGuardRegionId(), region.worldId()).join();
            String timeLeft = DurationFormatter.formatTimeLeft(leasehold != null ? leasehold.endDate() : null);
            builder.appendNewline()
                    .append(parseMiniMessage(
                            MessageKeys.LIST_RENTED_ENTRY,
                            "region", region.worldGuardRegionId(),
                            "time_left", timeLeft
                    ));
        }
    }

    private void appendFooter(@NotNull TextComponent.Builder builder, @NotNull String targetName,
                              @Nullable String category, int page, int totalPages) {
        Component previousComponent = page > 1
                ? buildNavComponent(MessageKeys.LIST_PREVIOUS, targetName, category, page - 1)
                : Component.empty();
        Component nextComponent = page < totalPages
                ? buildNavComponent(MessageKeys.LIST_NEXT, targetName, category, page + 1)
                : Component.empty();
        builder.appendNewline()
                .append(this.messages.component(MessageKeys.LIST_FOOTER,
                        "page", String.valueOf(page),
                        "total", String.valueOf(totalPages),
                        "previous", previousComponent,
                        "next", nextComponent));
    }

    private @NotNull Component buildNavComponent(@NotNull String key, @NotNull String playerName,
                                                 @Nullable String category, int targetPage) {
        StringBuilder command = new StringBuilder("/realty list");
        if (category != null) {
            command.append(' ').append(category);
        }
        command.append(" --page ").append(targetPage);
        command.append(" --player ").append(playerName);
        return parseMiniMessage(key,
                "command", command.toString());
    }

    /**
     * Renders a message whose placeholders sit inside a MiniMessage tag argument — the click
     * targets on the pagination links and result rows.
     *
     * <p>{@code Message.component} rewrites each placeholder into a generated MiniMessage tag and
     * lets a resolver fill it, which cannot reach inside another tag's argument. {@code format}
     * substitutes textually into the pattern first, so the finished string is deserialized here
     * with the click target already in place.</p>
     *
     * <p>Values passed this way are interpolated as markup rather than escaped, so they must be
     * plugin-authored: region ids, formatted prices and commands this class builds, never
     * player-supplied text.</p>
     */
    private @NotNull Component parseMiniMessage(@NotNull String key,
                                                @NotNull Object... replacements) {
        return MiniMessage.miniMessage().deserialize(this.messages.format(key, replacements));
    }
}
