package io.github.md5sha256.realty.listener;

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
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.party.PartyNames;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.localisation.MessageContainer;
import io.github.md5sha256.realty.localisation.MessageKeys;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

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
    private final MessageContainer messages;
    private final PartyService parties;

    public RegionNotificationListener(@NotNull RealtyEventDispatch events,
                                      @NotNull MessageContainer messages,
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

    @EventHandler
    public void onRegionBought(@NotNull RegionBoughtEvent event) {
        UUID seller = event.getPreviousTitleHolderId();
        if (seller == null) {
            return;
        }
        this.events.fireSync(new RealtyNotificationEvent(recipients(seller),
                this.messages.messageFor(MessageKeys.NOTIFICATION_REGION_BOUGHT,
                        Placeholder.unparsed("player", resolveName(event.getBuyerId())),
                        Placeholder.unparsed("price", CurrencyFormatter.format(event.getPrice())),
                        Placeholder.unparsed("region", event.getRegionId())),
                event.getRegion()));
    }

    @EventHandler
    public void onRegionRented(@NotNull RegionRentedEvent event) {
        this.events.fireSync(new RealtyNotificationEvent(recipients(event.getLandlordId()),
                this.messages.messageFor(MessageKeys.NOTIFICATION_REGION_RENTED,
                        Placeholder.unparsed("player", resolveName(event.getTenantId())),
                        Placeholder.unparsed("price", CurrencyFormatter.format(event.getPrice())),
                        Placeholder.unparsed("region", event.getRegionId())),
                event.getRegion()));
    }

    @EventHandler
    public void onRegionUnrented(@NotNull RegionUnrentedEvent event) {
        this.events.fireSync(new RealtyNotificationEvent(recipients(event.getLandlordId()),
                this.messages.messageFor(MessageKeys.NOTIFICATION_REGION_UNRENTED,
                        Placeholder.unparsed("player", resolveName(event.getTenantId())),
                        Placeholder.unparsed("region", event.getRegionId()),
                        Placeholder.unparsed("refund", CurrencyFormatter.format(event.getRefund()))),
                event.getRegion()));
    }

    @EventHandler
    public void onLeaseExpired(@NotNull LeaseExpiredEvent event) {
        this.events.fireSync(new RealtyNotificationEvent(recipients(event.getTenantId()),
                this.messages.messageFor(MessageKeys.NOTIFICATION_LEASEHOLD_EXPIRED,
                        Placeholder.unparsed("region", event.getRegionId())),
                event.getRegion()));
        this.events.fireSync(new RealtyNotificationEvent(recipients(event.getLandlordId()),
                this.messages.messageFor(MessageKeys.NOTIFICATION_LEASEHOLD_EXPIRED_LANDLORD,
                        Placeholder.unparsed("region", event.getRegionId())),
                event.getRegion()));
    }

    @EventHandler
    public void onModificationProposed(@NotNull LeaseModificationProposedEvent event) {
        if (LeaseholdRoles.LANDLORD.equals(event.getProposerRole())) {
            // Landlord proposed: notify the tenant, who decides by renewing or not.
            this.events.fireSync(new RealtyNotificationEvent(recipients(event.getTenantId()),
                    this.messages.messageFor(MessageKeys.NOTIFICATION_MODIFY_PROPOSED_LANDLORD,
                            Placeholder.unparsed("region", event.getRegionId())),
                    event.getRegion()));
        } else {
            // Tenant proposed: notify the landlord, who must accept or reject.
            this.events.fireSync(new RealtyNotificationEvent(recipients(event.getLandlordId()),
                    this.messages.messageFor(MessageKeys.NOTIFICATION_MODIFY_PROPOSED_TENANT,
                            Placeholder.unparsed("player", resolveName(event.getProposerId())),
                            Placeholder.unparsed("region", event.getRegionId())),
                    event.getRegion()));
        }
    }

    @EventHandler
    public void onModificationResolved(@NotNull LeaseModificationResolvedEvent event) {
        switch (event.getResolution()) {
            case "ACCEPTED" -> this.events.fireSync(new RealtyNotificationEvent(recipients(event.getTenantId()),
                    this.messages.messageFor(MessageKeys.NOTIFICATION_MODIFY_ACCEPTED,
                            Placeholder.unparsed("region", event.getRegionId())),
                    event.getRegion()));
            case "REJECTED" -> this.events.fireSync(new RealtyNotificationEvent(recipients(event.getTenantId()),
                    this.messages.messageFor(MessageKeys.NOTIFICATION_MODIFY_REJECTED,
                            Placeholder.unparsed("region", event.getRegionId())),
                    event.getRegion()));
            case "WITHDRAWN" -> {
                // Notify the party that did not withdraw.
                UUID target = LeaseholdRoles.LANDLORD.equals(event.getProposerRole())
                        ? event.getTenantId() : event.getLandlordId();
                this.events.fireSync(new RealtyNotificationEvent(recipients(target),
                        this.messages.messageFor(MessageKeys.NOTIFICATION_MODIFY_WITHDRAWN,
                                Placeholder.unparsed("region", event.getRegionId())),
                        event.getRegion()));
            }
            default -> { }
        }
    }

    @EventHandler
    public void onTerminationScheduled(@NotNull LeaseTerminationScheduledEvent event) {
        String date = event.getEffectiveDate().format(DateTimeFormatters.DATE_TIME);
        if (LeaseholdRoles.LANDLORD.equals(event.getTerminatedByRole())) {
            this.events.fireSync(new RealtyNotificationEvent(recipients(event.getTenantId()),
                    this.messages.messageFor(MessageKeys.NOTIFICATION_TERMINATION_SCHEDULED_TENANT,
                            Placeholder.unparsed("region", event.getRegionId()),
                            Placeholder.unparsed("date", date)),
                    event.getRegion()));
        } else {
            this.events.fireSync(new RealtyNotificationEvent(recipients(event.getLandlordId()),
                    this.messages.messageFor(MessageKeys.NOTIFICATION_TERMINATION_SCHEDULED_LANDLORD,
                            Placeholder.unparsed("region", event.getRegionId()),
                            Placeholder.unparsed("date", date)),
                    event.getRegion()));
        }
    }

    @EventHandler
    public void onTerminationCancelled(@NotNull LeaseTerminationCancelledEvent event) {
        // Notify the party that did not initiate the (now-cancelled) termination.
        UUID target = LeaseholdRoles.LANDLORD.equals(event.getTerminatedByRole())
                ? event.getTenantId() : event.getLandlordId();
        this.events.fireSync(new RealtyNotificationEvent(recipients(target),
                this.messages.messageFor(MessageKeys.NOTIFICATION_TERMINATION_CANCELLED,
                        Placeholder.unparsed("region", event.getRegionId())),
                event.getRegion()));
    }

    @EventHandler
    public void onLeaseTerminated(@NotNull LeaseTerminatedEvent event) {
        this.events.fireSync(new RealtyNotificationEvent(recipients(event.getTenantId()),
                this.messages.messageFor(MessageKeys.NOTIFICATION_LEASEHOLD_TERMINATED_TENANT,
                        Placeholder.unparsed("region", event.getRegionId()),
                        Placeholder.unparsed("refund", CurrencyFormatter.format(event.getRefund()))),
                event.getRegion()));
        this.events.fireSync(new RealtyNotificationEvent(recipients(event.getLandlordId()),
                this.messages.messageFor(MessageKeys.NOTIFICATION_LEASEHOLD_TERMINATED_LANDLORD,
                        Placeholder.unparsed("region", event.getRegionId())),
                event.getRegion()));
    }

    /**
     * Resolves a party's display name for use in notification text: a government's name, or the
     * player's username, falling back to the raw UUID when no name is known.
     */
    private @NotNull String resolveName(@NotNull UUID partyUuid) {
        return PartyNames.resolve(parties, partyUuid);
    }
}
