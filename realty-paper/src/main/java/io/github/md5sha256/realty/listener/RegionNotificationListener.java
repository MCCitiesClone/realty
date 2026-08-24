package io.github.md5sha256.realty.listener;

import com.google.inject.Inject;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.DateTimeFormatters;
import io.github.md5sha256.realty.api.LeaseholdRoles;
import io.github.md5sha256.realty.api.event.LeaseExpiredEvent;
import io.github.md5sha256.realty.api.event.LeaseModificationProposedEvent;
import io.github.md5sha256.realty.api.event.LeaseModificationResolvedEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminatedEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminationCancelledEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminationScheduledEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.api.event.RegionBoughtEvent;
import io.github.md5sha256.realty.api.event.RegionRentedEvent;
import io.github.md5sha256.realty.api.event.RegionUnrentedEvent;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.party.PartyNames;
import io.github.md5sha256.realty.party.PartyService;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Delivers counterparty notifications in response to Realty's own post-commit
 * lifecycle events. This decouples notification delivery from the command and
 * scheduler code that triggers the underlying actions: the actor's own command
 * feedback stays in the command, while the "your region was rented / bought /
 * unrented / expired" notices for the other party are centralised here and
 * driven entirely by events.
 */
public final class RegionNotificationListener implements Listener {

    private final RealtyEventDispatch events;
    private final Message messages;
    private final PartyService parties;

    @Inject
    public RegionNotificationListener(@NotNull RealtyEventDispatch events,
                                      @NotNull Message messages,
                                      @NotNull PartyService parties) {
        this.events = events;
        this.messages = messages;
        this.parties = parties;
    }

    /**
     * Expands a notification's addressee into the players who should actually receive it.
     *
     * <p>A player is addressed directly. A government's UUID belongs to nobody, so a message sent
     * to it would simply be dropped; it is delivered to the account's owner, members and
     * authorizers instead — the same people entitled to act for it.
     */
    private @NotNull List<UUID> recipients(@NotNull UUID partyUuid) {
        return List.copyOf(parties.domainMembers(partyUuid));
    }

    /**
     * Fires a notification, unless it has nobody to reach.
     *
     * <p>A player party is always its own recipient, so an empty list only happens for a government
     * whose membership could not be read — Treasury unreachable, an account with no members, or a
     * Vault-only server. {@link RealtyNotificationEvent} rejects an empty target list, so firing
     * anyway would throw out of the domain event that triggered this.
     */
    private void notify(@NotNull List<UUID> targets, @NotNull Component message,
                        @Nullable WorldGuardRegion region) {
        if (targets.isEmpty()) {
            return;
        }
        this.events.fireSync(new RealtyNotificationEvent(targets, message, region));
    }

    @EventHandler
    public void onRegionBought(@NotNull RegionBoughtEvent event) {
        UUID seller = event.getPreviousTitleHolderId();
        if (seller == null) {
            return;
        }
        notify(recipients(seller),
                this.messages.component(MessageKeys.NOTIFICATION_REGION_BOUGHT,
                        "player", resolveName(event.getBuyerId()),
                        "price", CurrencyFormatter.format(event.getPrice()),
                        "region", event.getRegionId()),
                event.getRegion());
    }

    @EventHandler
    public void onRegionRented(@NotNull RegionRentedEvent event) {
        notify(recipients(event.getLandlordId()),
                this.messages.component(MessageKeys.NOTIFICATION_REGION_RENTED,
                        "player", resolveName(event.getTenantId()),
                        "price", CurrencyFormatter.format(event.getPrice()),
                        "region", event.getRegionId()),
                event.getRegion());
    }

    @EventHandler
    public void onRegionUnrented(@NotNull RegionUnrentedEvent event) {
        notify(recipients(event.getLandlordId()),
                this.messages.component(MessageKeys.NOTIFICATION_REGION_UNRENTED,
                        "player", resolveName(event.getTenantId()),
                        "region", event.getRegionId(),
                        "refund", CurrencyFormatter.format(event.getRefund())),
                event.getRegion());
    }

    @EventHandler
    public void onLeaseExpired(@NotNull LeaseExpiredEvent event) {
        notify(recipients(event.getTenantId()),
                this.messages.component(MessageKeys.NOTIFICATION_LEASEHOLD_EXPIRED,
                        "region", event.getRegionId()),
                event.getRegion());
        notify(recipients(event.getLandlordId()),
                this.messages.component(MessageKeys.NOTIFICATION_LEASEHOLD_EXPIRED_LANDLORD,
                        "region", event.getRegionId()),
                event.getRegion());
    }

    @EventHandler
    public void onModificationProposed(@NotNull LeaseModificationProposedEvent event) {
        if (LeaseholdRoles.LANDLORD.equals(event.getProposerRole())) {
            // Landlord proposed: notify the tenant, who decides by renewing or not.
            notify(recipients(event.getTenantId()),
                    this.messages.component(MessageKeys.NOTIFICATION_MODIFY_PROPOSED_LANDLORD,
                            "region", event.getRegionId()),
                    event.getRegion());
        } else {
            // Tenant proposed: notify the landlord, who must accept or reject.
            notify(recipients(event.getLandlordId()),
                    this.messages.component(MessageKeys.NOTIFICATION_MODIFY_PROPOSED_TENANT,
                            "player", resolveName(event.getProposerId()),
                            "region", event.getRegionId()),
                    event.getRegion());
        }
    }

    @EventHandler
    public void onModificationResolved(@NotNull LeaseModificationResolvedEvent event) {
        switch (event.getResolution()) {
            case "ACCEPTED" -> notify(recipients(event.getTenantId()),
                    this.messages.component(MessageKeys.NOTIFICATION_MODIFY_ACCEPTED,
                            "region", event.getRegionId()),
                    event.getRegion());
            case "REJECTED" -> notify(recipients(event.getTenantId()),
                    this.messages.component(MessageKeys.NOTIFICATION_MODIFY_REJECTED,
                            "region", event.getRegionId()),
                    event.getRegion());
            case "WITHDRAWN" -> {
                // Notify the party that did not withdraw.
                UUID target = LeaseholdRoles.LANDLORD.equals(event.getProposerRole())
                        ? event.getTenantId() : event.getLandlordId();
                notify(recipients(target),
                        this.messages.component(MessageKeys.NOTIFICATION_MODIFY_WITHDRAWN,
                                "region", event.getRegionId()),
                        event.getRegion());
            }
            default -> { }
        }
    }

    @EventHandler
    public void onTerminationScheduled(@NotNull LeaseTerminationScheduledEvent event) {
        String date = event.getEffectiveDate().format(DateTimeFormatters.DATE_TIME);
        if (LeaseholdRoles.LANDLORD.equals(event.getTerminatedByRole())) {
            notify(recipients(event.getTenantId()),
                    this.messages.component(MessageKeys.NOTIFICATION_TERMINATION_SCHEDULED_TENANT,
                            "region", event.getRegionId(),
                            "date", date),
                    event.getRegion());
        } else {
            notify(recipients(event.getLandlordId()),
                    this.messages.component(MessageKeys.NOTIFICATION_TERMINATION_SCHEDULED_LANDLORD,
                            "region", event.getRegionId(),
                            "date", date),
                    event.getRegion());
        }
    }

    @EventHandler
    public void onTerminationCancelled(@NotNull LeaseTerminationCancelledEvent event) {
        // Notify the party that did not initiate the (now-cancelled) termination.
        UUID target = LeaseholdRoles.LANDLORD.equals(event.getTerminatedByRole())
                ? event.getTenantId() : event.getLandlordId();
        notify(recipients(target),
                this.messages.component(MessageKeys.NOTIFICATION_TERMINATION_CANCELLED,
                        "region", event.getRegionId()),
                event.getRegion());
    }

    @EventHandler
    public void onLeaseTerminated(@NotNull LeaseTerminatedEvent event) {
        notify(recipients(event.getTenantId()),
                this.messages.component(MessageKeys.NOTIFICATION_LEASEHOLD_TERMINATED_TENANT,
                        "region", event.getRegionId(),
                        "refund", CurrencyFormatter.format(event.getRefund())),
                event.getRegion());
        notify(recipients(event.getLandlordId()),
                this.messages.component(MessageKeys.NOTIFICATION_LEASEHOLD_TERMINATED_LANDLORD,
                        "region", event.getRegionId()),
                event.getRegion());
    }

    /**
     * Resolves a party's display name for use in notification text: a government's name, or the
     * player's username, falling back to the raw UUID when no name is known.
     */
    private @NotNull String resolveName(@NotNull UUID partyUuid) {
        return PartyNames.resolve(parties, partyUuid);
    }
}
