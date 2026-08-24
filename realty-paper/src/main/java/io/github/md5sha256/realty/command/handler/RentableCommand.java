package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Handles {@code /realty rentable <accepting> [region]}. */
@Command({"realty", "rl"})
public final class RentableCommand implements CommandHandler {

    private final RealtyPaperApi api;
    private final Message messages;

    @Inject
    public RentableCommand(@NotNull RealtyPaperApi api, @NotNull Message messages) {
        this.api = api;
        this.messages = messages;
    }

    @Route("rentable <accepting> [region]")
    @Permission("realty.command.rentable")
    @Description("Set whether a leasehold is accepting tenants")
    public void rentable(@Sender CommandSender rawSender,
                         @Arg("accepting") boolean accepting,
                         @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        // Declared as CommandSender rather than Player so the console gets Realty's own
        // players-only message; @Sender Player would have the framework reject it with its.
        if (!(rawSender instanceof Player sender)) {
            rawSender.sendMessage(this.messages.component(MessageKeys.COMMON_PLAYERS_ONLY));
            return;
        }
        WorldGuardRegion region = WorldGuardRegionResolver.resolveOrStandingIn(namedRegion, sender);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        boolean bypass = sender.hasPermission("realty.command.rentable.others");
        String regionId = region.region().getId();
        this.api.setRentable(regionId, region.world().getUID(), sender.getUniqueId(), bypass, accepting)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyBackend.SetRentableResult.Success success ->
                                sender.sendMessage(this.messages.component(success.acceptingTenants()
                                                ? MessageKeys.RENTABLE_ENABLED : MessageKeys.RENTABLE_DISABLED,
                                        "region", regionId));
                        case RealtyBackend.SetRentableResult.NoChange noChange ->
                                sender.sendMessage(this.messages.component(noChange.acceptingTenants()
                                                ? MessageKeys.RENTABLE_ALREADY_ENABLED
                                                : MessageKeys.RENTABLE_ALREADY_DISABLED,
                                        "region", regionId));
                        case RealtyBackend.SetRentableResult.NoLeaseholdContract ignored ->
                                sender.sendMessage(this.messages.component(
                                        MessageKeys.RENTABLE_NO_LEASEHOLD_CONTRACT, "region", regionId));
                        case RealtyBackend.SetRentableResult.NotAuthorized ignored ->
                                sender.sendMessage(this.messages.component(
                                        MessageKeys.RENTABLE_NOT_LANDLORD, "region", regionId));
                        case RealtyBackend.SetRentableResult.UpdateFailed ignored ->
                                sender.sendMessage(this.messages.component(
                                        MessageKeys.RENTABLE_UPDATE_FAILED, "region", regionId));
                    }
                }).exceptionally(ex -> {
                    sender.sendMessage(this.messages.component(MessageKeys.RENTABLE_ERROR,
                            "error", String.valueOf(ex.getMessage())));
                    return null;
                });
    }
}
