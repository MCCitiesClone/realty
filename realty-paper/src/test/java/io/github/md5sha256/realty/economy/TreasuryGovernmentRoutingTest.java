package io.github.md5sha256.realty.economy;

import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Money must reach a government's own treasury when it holds a region, and must never be routed
 * through a personal account opened against a UUID no player owns.
 */
@ExtendWith(MockitoExtension.class)
class TreasuryGovernmentRoutingTest {

    private static final int GOVERNMENT_ACCOUNT_ID = 77;

    @Mock
    private TreasuryApi treasuryApi;

    private TreasuryEconomyProvider provider;

    private final UUID player = UUID.randomUUID();
    private final UUID governmentOwner = UUID.randomUUID();
    private final UUID governmentParty = GovernmentPartyEntity.partyIdFor(GOVERNMENT_ACCOUNT_ID);

    @BeforeEach
    void setUp() {
        GovernmentAccountLookup lookup = partyUuid -> partyUuid.equals(governmentParty)
                ? OptionalInt.of(GOVERNMENT_ACCOUNT_ID)
                : OptionalInt.empty();
        provider = new TreasuryEconomyProvider(treasuryApi, lookup);
    }

    private Account account(int id, AccountType type, UUID owner) {
        Account a = new Account();
        a.setAccountId(id);
        a.setAccountType(type);
        a.setOwnerUuid(owner);
        return a;
    }

    private TransferRequest captureTransfer() {
        ArgumentCaptor<TransferRequest> request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasuryApi).transfer(request.capture());
        return request.getValue();
    }

    @Test
    void governmentLandlord_receivesRentInItsOwnAccount() {
        Account payerPersonal = account(1, AccountType.PERSONAL, player);
        when(treasuryApi.resolveOrCreatePersonal(player)).thenReturn(payerPersonal);
        when(treasuryApi.getAccountById(GOVERNMENT_ACCOUNT_ID))
                .thenReturn(account(GOVERNMENT_ACCOUNT_ID, AccountType.GOVERNMENT, governmentOwner));
        when(treasuryApi.transfer(any())).thenReturn(1L);

        PaymentResult result = provider.transfer(player, governmentParty, 50.0, "Rental Payment: PLOT");

        assertInstanceOf(PaymentResult.Success.class, result);
        assertEquals(GOVERNMENT_ACCOUNT_ID, captureTransfer().toAccountId());
        // The synthetic party UUID owns nothing; opening a personal account for it would strand
        // the money somewhere no player can reach.
        verify(treasuryApi, never()).resolveOrCreatePersonal(governmentParty);
    }

    @Test
    void governmentTenant_paysFromItsOwnAccount() {
        Account landlordPersonal = account(2, AccountType.PERSONAL, player);
        when(treasuryApi.getAccountById(GOVERNMENT_ACCOUNT_ID))
                .thenReturn(account(GOVERNMENT_ACCOUNT_ID, AccountType.GOVERNMENT, governmentOwner));
        when(treasuryApi.getAccountsByOwner(player)).thenReturn(java.util.List.of(landlordPersonal));
        when(treasuryApi.transfer(any())).thenReturn(2L);

        PaymentResult result = provider.transfer(governmentParty, player, 25.0, "Rental Payment: PLOT");

        assertInstanceOf(PaymentResult.Success.class, result);
        TransferRequest request = captureTransfer();
        assertEquals(GOVERNMENT_ACCOUNT_ID, request.fromAccountId());
        assertEquals(landlordPersonal.getAccountId(), request.toAccountId());
        // Treasury has no player behind the party UUID, so the account owner is recorded instead.
        assertEquals(governmentOwner, request.initiator());
    }

    @Test
    void governmentBalance_readsTheGovernmentAccount() {
        when(treasuryApi.getBalanceByAccountId(GOVERNMENT_ACCOUNT_ID))
                .thenReturn(new BigDecimal("1200.50"));

        assertEquals(1200.50, provider.getBalance(governmentParty));
        // Resolving by owner UUID would find nothing and silently report a zero balance.
        verify(treasuryApi, never()).getBalanceByOwnerUuid(governmentParty);
    }

    @Test
    void playerParties_areUnaffected() {
        Account payerPersonal = account(1, AccountType.PERSONAL, player);
        UUID recipient = UUID.randomUUID();
        Account recipientPersonal = account(3, AccountType.PERSONAL, recipient);
        when(treasuryApi.resolveOrCreatePersonal(player)).thenReturn(payerPersonal);
        when(treasuryApi.getAccountsByOwner(recipient)).thenReturn(java.util.List.of(recipientPersonal));
        when(treasuryApi.transfer(any())).thenReturn(3L);

        assertInstanceOf(PaymentResult.Success.class,
                provider.transfer(player, recipient, 10.0, "Plot Purchase: PLOT"));

        TransferRequest request = captureTransfer();
        assertEquals(payerPersonal.getAccountId(), request.fromAccountId());
        assertEquals(recipientPersonal.getAccountId(), request.toAccountId());
        assertEquals(player, request.initiator());
    }
}
