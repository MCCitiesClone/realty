package io.github.md5sha256.realty.database;

import io.github.md5sha256.realty.api.PartyAuthorizer;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyBackend.OfferResult;
import io.github.md5sha256.realty.api.RealtyBackend.SetRentableResult;
import io.github.md5sha256.realty.api.RealtyBackend.ToggleOffersResult;
import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Treasury government standing in for a player as a region's authority, landlord, title holder or
 * tenant: the registration that ties its synthetic UUID to an account, and the authorization that
 * lets the account's members act as it.
 */
class GovernmentPartyTest extends AbstractDatabaseTest {

    private static final UUID WORLD_ID = UUID.randomUUID();
    private static final int ACCOUNT_ID = 501;
    private static final UUID GOVERNMENT = GovernmentPartyEntity.partyIdFor(ACCOUNT_ID);
    private static final UUID GOVERNMENT_MEMBER = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();
    private static final UUID TITLE_HOLDER = UUID.randomUUID();

    private static final AtomicInteger REGION_COUNTER = new AtomicInteger();

    /**
     * A backend whose authorizer treats {@link #GOVERNMENT_MEMBER} as entitled to act for
     * {@link #GOVERNMENT}, standing in for the Treasury-backed authorizer the Paper layer supplies.
     */
    private static RealtyBackend governmentAware;

    private static String uniqueRegionId() {
        return "gov_region_" + REGION_COUNTER.incrementAndGet();
    }

    @BeforeEach
    void createGovernmentAwareBackend() {
        PartyAuthorizer authorizer = (actorId, partyId) -> {
            if (partyId.equals(GOVERNMENT)) {
                return actorId.equals(GOVERNMENT_MEMBER);
            }
            return actorId.equals(partyId);
        };
        governmentAware = new RealtyBackendImpl(database,
                uuid -> CompletableFuture.completedFuture(uuid.toString()),
                LocalDateTime::toString, () -> 86400, authorizer);
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("derives the party UUID from the account id")
        void derivesPartyId() {
            GovernmentPartyEntity party = logic.registerGovernmentParty(ACCOUNT_ID, "Springfield");

            Assertions.assertEquals(GovernmentPartyEntity.partyIdFor(ACCOUNT_ID), party.partyUuid());
            Assertions.assertEquals(ACCOUNT_ID, party.accountId());
            Assertions.assertEquals("Springfield", party.displayName());
        }

        @Test
        @DisplayName("is idempotent and refreshes the display name")
        void isIdempotent() {
            logic.registerGovernmentParty(ACCOUNT_ID, "Springfield");
            GovernmentPartyEntity renamed = logic.registerGovernmentParty(ACCOUNT_ID, "Springfield City");

            Assertions.assertEquals(GOVERNMENT, renamed.partyUuid());
            List<GovernmentPartyEntity> all = logic.getGovernmentParties();
            Assertions.assertEquals(1, all.size(), "re-registering must not create a second party");
            Assertions.assertEquals("Springfield City", all.get(0).displayName());
        }

        @Test
        @DisplayName("resolves by party UUID and by account id")
        void resolvesBothWays() {
            logic.registerGovernmentParty(ACCOUNT_ID, "Springfield");

            GovernmentPartyEntity byUuid = logic.getGovernmentParty(GOVERNMENT);
            GovernmentPartyEntity byAccount = logic.getGovernmentPartyByAccountId(ACCOUNT_ID);
            Assertions.assertNotNull(byUuid);
            Assertions.assertNotNull(byAccount);
            Assertions.assertEquals(ACCOUNT_ID, byUuid.accountId());
            Assertions.assertEquals(GOVERNMENT, byAccount.partyUuid());
        }

        @Test
        @DisplayName("returns null for an ordinary player UUID")
        void playerIsNotAGovernment() {
            logic.registerGovernmentParty(ACCOUNT_ID, "Springfield");

            Assertions.assertNull(logic.getGovernmentParty(OUTSIDER));
        }

        @Test
        @DisplayName("unregisters")
        void unregisters() {
            logic.registerGovernmentParty(ACCOUNT_ID, "Springfield");

            Assertions.assertEquals(1, logic.deleteGovernmentParty(GOVERNMENT));
            Assertions.assertNull(logic.getGovernmentParty(GOVERNMENT));
            Assertions.assertEquals(0, logic.deleteGovernmentParty(GOVERNMENT));
        }
    }

    @Nested
    @DisplayName("a government landlord")
    class GovernmentLandlord {

        @Test
        @DisplayName("may be set as the landlord and lets its members act as it")
        void memberActsAsLandlord() {
            String regionId = uniqueRegionId();
            Assertions.assertTrue(
                    governmentAware.createLeasehold(regionId, WORLD_ID, 100.0, 604800, 3, GOVERNMENT));

            SetRentableResult result = governmentAware.setRentable(
                    regionId, WORLD_ID, GOVERNMENT_MEMBER, false, false);

            Assertions.assertInstanceOf(SetRentableResult.Success.class, result);
        }

        @Test
        @DisplayName("refuses a player who cannot access the account")
        void outsiderIsRefused() {
            String regionId = uniqueRegionId();
            Assertions.assertTrue(
                    governmentAware.createLeasehold(regionId, WORLD_ID, 100.0, 604800, 3, GOVERNMENT));

            SetRentableResult result = governmentAware.setRentable(
                    regionId, WORLD_ID, OUTSIDER, false, false);

            Assertions.assertInstanceOf(SetRentableResult.NotAuthorized.class, result);
        }

        @Test
        @DisplayName("grants its members the add/remove authority check")
        void memberPassesRegionAuthorityCheck() {
            String regionId = uniqueRegionId();
            Assertions.assertTrue(
                    governmentAware.createLeasehold(regionId, WORLD_ID, 100.0, 604800, 3, GOVERNMENT));

            Assertions.assertTrue(
                    governmentAware.checkRegionAuthority(regionId, WORLD_ID, GOVERNMENT_MEMBER));
            Assertions.assertFalse(
                    governmentAware.checkRegionAuthority(regionId, WORLD_ID, OUTSIDER));
        }
    }

    @Nested
    @DisplayName("a government authority")
    class GovernmentAuthority {

        @Test
        @DisplayName("lets its members manage offers on the region")
        void memberTogglesOffers() {
            String regionId = uniqueRegionId();
            Assertions.assertTrue(governmentAware.createFreehold(
                    regionId, WORLD_ID, 1000.0, GOVERNMENT, TITLE_HOLDER));

            ToggleOffersResult allowed = governmentAware.toggleOffers(
                    regionId, WORLD_ID, GOVERNMENT_MEMBER, false, false);
            ToggleOffersResult refused = governmentAware.toggleOffers(
                    regionId, WORLD_ID, OUTSIDER, false, false);

            Assertions.assertInstanceOf(ToggleOffersResult.Success.class, allowed);
            Assertions.assertInstanceOf(ToggleOffersResult.NotSanctioned.class, refused);
        }

        @Test
        @DisplayName("still lets its members buy and bid personally")
        void memberMayStillTransactPersonally() {
            String regionId = uniqueRegionId();
            Assertions.assertTrue(governmentAware.createFreehold(
                    regionId, WORLD_ID, 1000.0, GOVERNMENT, null));

            // A member acting for the government is not the government itself: an offer on a plot
            // their government oversees is an ordinary transaction between two distinct parties,
            // so the "offerer must not be the authority" exclusion must not catch them.
            OfferResult result = governmentAware.placeOffer(
                    regionId, WORLD_ID, GOVERNMENT_MEMBER, 900.0);

            Assertions.assertInstanceOf(OfferResult.Success.class, result);
        }
    }
}
