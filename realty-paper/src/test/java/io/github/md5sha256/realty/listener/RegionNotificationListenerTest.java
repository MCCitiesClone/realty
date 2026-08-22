package io.github.md5sha256.realty.listener;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.LeaseExpiredEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.party.PartyService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notification addressing when a party is a government rather than a player.
 *
 * <p>A government's UUID reaches nobody, so notifications go to the people who run its account.
 * When that membership cannot be read there is no one to tell — and
 * {@link RealtyNotificationEvent} rejects an empty target list, so the notification has to be
 * dropped rather than fired. Firing anyway throws out of whichever domain event triggered it: a
 * lease-expiry sweep would abort partway through.
 */
class RegionNotificationListenerTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID government = UUID.randomUUID();
    private final UUID governmentMember = UUID.randomUUID();

    private RealtyEventDispatch events;
    private PartyService parties;
    private RegionNotificationListener listener;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        events = mock(RealtyEventDispatch.class);
        parties = mock(PartyService.class);
        Message messages = mock(Message.class);
        lenient().when(messages.component(any(), any(Object[].class))).thenReturn(Component.empty());
        // Names are rendered through Bukkit for player parties; no server exists here.
        bukkit = Mockito.mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
        bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(null);
        listener = new RegionNotificationListener(events, messages, parties);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private LeaseExpiredEvent leaseExpired() {
        // A real ProtectedRegion: the listener reads its id for the message placeholders.
        WorldGuardRegion region = new WorldGuardRegion(
                new ProtectedCuboidRegion("plot", BlockVector3.ZERO, BlockVector3.at(16, 16, 16)),
                mock(World.class));
        return new LeaseExpiredEvent(region, tenant, government);
    }

    @Test
    @DisplayName("a government landlord is notified through its account members")
    void governmentLandlordNotifiesItsMembers() {
        when(parties.domainMembers(tenant)).thenReturn(Set.of(tenant));
        when(parties.domainMembers(government)).thenReturn(Set.of(governmentMember));
        when(parties.governmentName(any())).thenReturn(java.util.Optional.empty());

        listener.onLeaseExpired(leaseExpired());

        ArgumentCaptor<RealtyNotificationEvent> fired =
                ArgumentCaptor.forClass(RealtyNotificationEvent.class);
        verify(events, times(2)).fireSync(fired.capture());
        List<UUID> landlordTargets = fired.getAllValues().get(1).getTargets();
        assertEquals(List.of(governmentMember), landlordTargets,
                "the government's UUID reaches nobody; its members must be addressed instead");
    }

    @Test
    @DisplayName("a notification with no reachable recipient is dropped, not fired")
    void unreachableGovernmentIsSkipped() {
        when(parties.domainMembers(tenant)).thenReturn(Set.of(tenant));
        // Treasury unreachable, an account with no members, or a Vault-only server.
        when(parties.domainMembers(government)).thenReturn(Set.of());
        when(parties.governmentName(any())).thenReturn(java.util.Optional.empty());

        listener.onLeaseExpired(leaseExpired());

        // The tenant still hears about it; only the unreachable landlord notification is dropped.
        ArgumentCaptor<RealtyNotificationEvent> fired =
                ArgumentCaptor.forClass(RealtyNotificationEvent.class);
        verify(events, times(1)).fireSync(fired.capture());
        assertEquals(List.of(tenant), fired.getValue().getTargets());
    }

    @Test
    @DisplayName("nothing is fired when no party can be reached at all")
    void noReachablePartiesFiresNothing() {
        when(parties.domainMembers(any(UUID.class))).thenReturn(Set.of());
        when(parties.governmentName(any())).thenReturn(java.util.Optional.empty());

        listener.onLeaseExpired(leaseExpired());

        verify(events, never()).fireSync(any(RealtyNotificationEvent.class));
    }
}
