package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionCreateEvent;
import io.github.md5sha256.realty.api.event.RegionCreatedEvent;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.github.md5sha256.realty.command.util.NamedAuthority;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.settings.Settings;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Flag;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /realty register …} family: putting an existing WorldGuard region under Realty.
 *
 * <p>Distinct from {@code /realty create}, which makes the WorldGuard region as well.</p>
 */
@Command({"realty", "rl"})
public final class RegisterCommands implements CommandHandler {

    private final RealtyPaperApi api;
    private final AtomicReference<Settings> settings;
    private final Message messages;
    private final RealtyEventDispatch events;

    @Inject
    public RegisterCommands(@NotNull RealtyPaperApi api,
                            @NotNull AtomicReference<Settings> settings,
                            @NotNull Message messages,
                            @NotNull RealtyEventDispatch events) {
        this.api = api;
        this.settings = settings;
        this.messages = messages;
        this.events = events;
    }

    @Route("register leasehold <price> <period> <maxrenewals> [region]")
    @Permission("realty.command.register.leasehold")
    @Description("Register a region as a leasehold")
    public void leasehold(@Sender CommandSender sender,
                          @Arg(value = "price", min = 0.01) double price,
                          @Arg("period") Duration period,
                          @Arg(value = "maxrenewals", min = -1) int maxExtensions,
                          @Flag("landlord") @Nullable NamedAuthority landlordFlag,
                          @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {
        UUID landlord = landlordFlag != null ? landlordFlag.uuid()
                : this.settings.get().defaultLeaseholdAuthority();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        if (sender instanceof Player player
                && !this.events.fireSync(new RegionCreateEvent(region, player.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.registerLeasehold(region, price, period.toSeconds(), maxExtensions, landlord)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyPaperApi.CreateLeaseholdResult.Success ignored -> {
                                sender.sendMessage(this.messages.component(MessageKeys.REGISTER_RENTAL_SUCCESS));
                                if (sender instanceof Player player) {
                                    this.events.fireSync(new RegionCreatedEvent(region, player.getUniqueId()));
                                }
                        }
                        case RealtyPaperApi.CreateLeaseholdResult.AlreadyRegistered ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REGISTER_RENTAL_ALREADY_REGISTERED));
                        case RealtyPaperApi.CreateLeaseholdResult.Error error ->
                                sender.sendMessage(this.messages.component(MessageKeys.REGISTER_RENTAL_ERROR,
                                        "error", error.message()));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    sender.sendMessage(this.messages.component(MessageKeys.REGISTER_RENTAL_ERROR,
                            "error", cause.getMessage()));
                    return null;
                });
    }

    @Route("register freehold [region]")
    @Permission("realty.command.register.freehold")
    @Description("Register a region as a freehold")
    public void freehold(@Sender CommandSender sender,
                         @Flag(value = "price", min = 0.01) @Nullable Double priceFlag,
                         @Flag("titleholder") @Nullable NamedAuthority titleHolderFlag,
                         @Flag("authority") @Nullable NamedAuthority authorityFlag,
                         @OptionalArg("region") @Nullable WorldGuardRegion namedRegion) {

                Double price = priceFlag;
        UUID authority = authorityFlag != null ? authorityFlag.uuid()
                : this.settings.get().defaultFreeholdAuthority();
        UUID titleholder = titleHolderFlag != null ? titleHolderFlag.uuid()
                : this.settings.get().defaultFreeholdTitleholder();
        WorldGuardRegion region = namedRegion != null ? namedRegion
                : (sender instanceof Player player
                        ? WorldGuardRegionResolver.regionAt(player.getLocation()) : null);
        if (region == null) {
            sender.sendMessage(this.messages.component(MessageKeys.ERROR_NO_REGION));
            return;
        }
        if (sender instanceof Player player
                && !this.events.fireSync(new RegionCreateEvent(region, player.getUniqueId()))) {
            sender.sendMessage(this.messages.component(MessageKeys.COMMON_ACTION_CANCELLED));
            return;
        }
        this.api.registerFreehold(region, price, authority, titleholder)
                .thenAccept(result -> {
                    switch (result) {
                        case RealtyPaperApi.CreateFreeholdResult.Success ignored -> {
                                sender.sendMessage(this.messages.component(MessageKeys.REGISTER_FREEHOLD_SUCCESS));
                                if (sender instanceof Player player) {
                                    this.events.fireSync(new RegionCreatedEvent(region, player.getUniqueId()));
                                }
                        }
                        case RealtyPaperApi.CreateFreeholdResult.AlreadyRegistered ignored ->
                                sender.sendMessage(this.messages.component(MessageKeys.REGISTER_FREEHOLD_ALREADY_REGISTERED));
                        case RealtyPaperApi.CreateFreeholdResult.Error error ->
                                sender.sendMessage(this.messages.component(MessageKeys.REGISTER_FREEHOLD_ERROR,
                                        "error", error.message()));
                    }
                }).exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    sender.sendMessage(this.messages.component(MessageKeys.REGISTER_FREEHOLD_ERROR,
                            "error", cause.getMessage()));
                    return null;
                });
    }
}
