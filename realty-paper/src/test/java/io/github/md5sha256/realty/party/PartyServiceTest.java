package io.github.md5sha256.realty.party;

import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PartyServiceTest {

    private static final int ACCOUNT_ID = 42;

    @Mock
    private RealtyBackend backend;

    @Mock
    private TreasuryApi treasuryApi;

    private final UUID governmentParty = GovernmentPartyEntity.partyIdFor(ACCOUNT_ID);
    private final UUID accountOwner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID authorizer = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();

    private PartyService service;

    private PartyService serviceWith(TreasuryApi treasury) {
        PartyService created = new PartyService(() -> backend, treasury,
                uuid -> CompletableFuture.completedFuture("player-" + uuid), Logger.getGlobal());
        created.refresh();
        return created;
    }

    private AccountMember accountMember(UUID uuid) {
        return new AccountMember(ACCOUNT_ID, uuid, accountOwner, Instant.EPOCH);
    }

    @BeforeEach
    void setUp() {
        when(backend.getGovernmentParties()).thenReturn(
                List.of(new GovernmentPartyEntity(governmentParty, ACCOUNT_ID, "Springfield")));
        service = serviceWith(treasuryApi);
    }

    @Test
    void partyIdIsDerivedFromTheAccountAndIsStable() {
        assertEquals(GovernmentPartyEntity.partyIdFor(ACCOUNT_ID),
                GovernmentPartyEntity.partyIdFor(ACCOUNT_ID));
        assertFalse(GovernmentPartyEntity.partyIdFor(ACCOUNT_ID)
                .equals(GovernmentPartyEntity.partyIdFor(ACCOUNT_ID + 1)));
    }

    @Test
    void unregisteredUuidIsAPlayer() {
        assertFalse(service.isGovernment(outsider));
        assertTrue(service.accountId(outsider).isEmpty());
        assertTrue(service.governmentName(outsider).isEmpty());
    }

    @Test
    void registeredUuidResolvesToItsGovernment() {
        assertTrue(service.isGovernment(governmentParty));
        assertEquals(ACCOUNT_ID, service.accountId(governmentParty).getAsInt());
        assertEquals("Springfield", service.governmentName(governmentParty).orElseThrow());
        assertEquals("Springfield", service.partyByName("springfield").orElseThrow().displayName());
    }

    @Test
    void aNameWithSpacesIsReachableAsOneToken() {
        int accountId = 7;
        UUID party = GovernmentPartyEntity.partyIdFor(accountId);
        when(backend.getGovernmentParties()).thenReturn(
                List.of(new GovernmentPartyEntity(party, accountId, "Springfield City")));
        PartyService spaced = serviceWith(treasuryApi);

        // A command argument is a single token, so gov:SpringfieldCity has to reach it.
        assertEquals(party, spaced.partyByName("SpringfieldCity").orElseThrow().partyUuid());
        assertEquals(party, spaced.partyByName("springfieldcity").orElseThrow().partyUuid());
        assertEquals(party, spaced.partyByName("Springfield City").orElseThrow().partyUuid());
        assertEquals("SpringfieldCity",
                PartyService.commandName(spaced.parties().get(0)));
    }

    @Test
    void playerPartyIsOnlyItself() {
        assertTrue(service.actsFor(outsider, outsider));
        assertFalse(service.actsFor(outsider, UUID.randomUUID()));
    }

    @Test
    void governmentPartyDefersToTreasuryAccess() {
        when(treasuryApi.canAccessAccount(member, ACCOUNT_ID)).thenReturn(true);
        when(treasuryApi.canAccessAccount(outsider, ACCOUNT_ID)).thenReturn(false);

        assertTrue(service.actsFor(member, governmentParty));
        assertFalse(service.actsFor(outsider, governmentParty));
    }

    @Test
    void governmentPartyIsRefusedWhenTreasuryFails() {
        when(treasuryApi.canAccessAccount(member, ACCOUNT_ID))
                .thenThrow(new IllegalStateException("treasury down"));

        // Failing open would hand control of every government-held region to anyone.
        assertFalse(service.actsFor(member, governmentParty));
    }

    @Test
    void governmentPartyIsRefusedWithoutTreasury() {
        assertFalse(serviceWith(null).actsFor(member, governmentParty));
    }

    @Test
    void domainMembersExpandAGovernmentIntoItsPeople() {
        Account account = new Account();
        account.setAccountId(ACCOUNT_ID);
        account.setAccountType(AccountType.GOVERNMENT);
        account.setOwnerUuid(accountOwner);
        when(treasuryApi.getAccountById(ACCOUNT_ID)).thenReturn(account);
        when(treasuryApi.getMembers(ACCOUNT_ID)).thenReturn(List.of(accountMember(member)));
        when(treasuryApi.getAuthorizers(ACCOUNT_ID)).thenReturn(List.of(accountMember(authorizer)));

        assertEquals(Set.of(accountOwner, member, authorizer), service.domainMembers(governmentParty));
    }

    @Test
    void domainMembersOfAPlayerAreJustThatPlayer() {
        assertEquals(Set.of(outsider), service.domainMembers(outsider));
    }

    @Test
    void refreshDropsAPartyThatIsNoLongerRegistered() {
        when(backend.getGovernmentParties()).thenReturn(List.of());
        service.refresh();

        assertFalse(service.isGovernment(governmentParty));
    }

    @Test
    void refreshKeepsTheCachedSetWhenTheDatabaseFails() {
        when(backend.getGovernmentParties()).thenThrow(new IllegalStateException("db down"));
        service.refresh();

        // Dropping the cache would silently demote every government to an unrecognised UUID,
        // sending its rent to a personal account that does not exist.
        assertTrue(service.isGovernment(governmentParty));
    }

    @Test
    void displayNameUsesTheGovernmentNameAndFallsBackToThePlayerResolver() {
        assertEquals("Springfield", service.displayName(governmentParty).join());
        assertEquals("player-" + outsider, service.displayName(outsider).join());
    }
}
