package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionUnrentEvent;
import io.github.md5sha256.realty.api.event.RegionUnrentedEvent;
import io.github.md5sha256.realty.command.util.WorldGuardRegionResolver;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import org.incendo.cloud.paper.util.sender.Source;

import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * Handles {@code /realty unrent [region]}.
 *
 * <p>Removes the tenant from a leased region, clears WorldGuard members,
 * provides a prorated refund, and updates the region sign.
 * Permission: {@code realty.command.unrent}.</p>
 */
public record UnrentCommand(
        @NotNull RealtyPaperApi api,
        @NotNull Message messages,
        @NotNull RealtyEventDispatch events
) implements CustomCommandBean.Single {

    @Override
    public @NotNull Command<? extends Source> command(@NotNull Command.Builder<Source> builder) {
        return builder
                .literal("unrent")
                .permission("realty.command.unrent")
                .optional("region", WorldGuardRegionResolver.worldGuardRegionResolver())
                .handler(this::execute)
                .build();
    }

    private void execute(@NotNull CommandContext<Source> ctx) {
        if (!(ctx.sender().source() instanceof Player sender)) {
            ctx.sender().source().sendMessage(messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = ctx.<WorldGuardRegion>optional("region")
                .orElseGet(() -> WorldGuardRegionResolver.resolveAtLocation(sender.getLocation()));
        if (region == null) {
            sender.sendMessage(messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        if (!region.region().getOwners().contains(sender.getUniqueId())) {
            sender.sendMessage(messages.component(MessageKeys.UNRENT_NOT_TENANT,
                    "region", region.region().getId()));
            return;
        }
        // Cancellable pre-event (main thread); a veto stops the action before the API is called.
        if (!events.fireSync(new RegionUnrentEvent(region, sender.getUniqueId()))) {
            sender.sendMessage(messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        api.unrent(region, sender.getUniqueId()).thenAccept(result -> {
            switch (result) {
                case RealtyPaperApi.UnrentResult.Success success -> {
                    sender.sendMessage(messages.component(MessageKeys.UNRENT_SUCCESS,
                            "region", success.regionId(),
                            "refund", CurrencyFormatter.format(success.refund())));
                    // Post-event; fireSync hops to the main thread. RegionNotificationListener notifies the landlord.
                    events.fireSync(new RegionUnrentedEvent(region, sender.getUniqueId(),
                            success.landlordId(), success.refund()));
                }
                case RealtyPaperApi.UnrentResult.NoLeaseholdContract noContract ->
                        sender.sendMessage(messages.component(MessageKeys.UNRENT_NO_LEASEHOLD_CONTRACT,
                                "region", noContract.regionId()));
                case RealtyPaperApi.UnrentResult.RefundFailed failed ->
                        sender.sendMessage(messages.component(MessageKeys.UNRENT_REFUND_FAILED,
                                "error", failed.error()));
                case RealtyPaperApi.UnrentResult.UpdateFailed updateFailed ->
                        sender.sendMessage(messages.component(MessageKeys.UNRENT_UPDATE_FAILED,
                                "region", updateFailed.regionId()));
                case RealtyPaperApi.UnrentResult.Error error ->
                        sender.sendMessage(messages.component(MessageKeys.UNRENT_ERROR,
                                "error", error.message()));
            }
        });
    }

}
