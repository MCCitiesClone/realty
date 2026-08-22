package io.github.md5sha256.realty.economy;

import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Economy provider backed by Treasury. Provides full ledger support:
 * each transfer is recorded with a human-readable message that appears
 * in the player's Treasury transaction history.
 * <p>
 * Account resolution: a party UUID that names a Treasury GOVERNMENT account
 * (see {@link GovernmentAccountLookup}) resolves straight to that account, on
 * either side of the transfer — a government pays and is paid from its own
 * treasury, never a personal balance. Otherwise the payer is resolved as a
 * personal account (created with starting balance if missing), and the
 * recipient is resolved by
 * preferring its GOVERNMENT account, then PERSONAL, then BUSINESS — so
 * government landlords (legacy DCGovernment-style real UUIDs that own both a
 * personal and a government account) route income to their government
 * treasury, while ordinary landlords still get their personal balance rather
 * than a firm BUSINESS account they happen to own.
 */
public final class TreasuryEconomyProvider implements EconomyProvider {

    private static final String PLUGIN_SYSTEM = "realty";

    private final TreasuryApi treasuryApi;
    private final GovernmentAccountLookup governmentAccounts;

    public TreasuryEconomyProvider(@NotNull TreasuryApi treasuryApi) {
        this(treasuryApi, GovernmentAccountLookup.NONE);
    }

    /**
     * @param governmentAccounts resolves party UUIDs that stand for Treasury GOVERNMENT accounts,
     *                           so a government holding a region is paid into (and charged from)
     *                           its own account
     */
    public TreasuryEconomyProvider(@NotNull TreasuryApi treasuryApi,
                                    @NotNull GovernmentAccountLookup governmentAccounts) {
        this.treasuryApi = treasuryApi;
        this.governmentAccounts = governmentAccounts;
    }

    @Override
    public double getBalance(@NotNull UUID playerId) {
        OptionalInt governmentAccountId = governmentAccounts.accountId(playerId);
        if (governmentAccountId.isPresent()) {
            BigDecimal balance = treasuryApi.getBalanceByAccountId(governmentAccountId.getAsInt());
            return balance != null ? balance.doubleValue() : 0.0;
        }
        if (!treasuryApi.hasAccountByOwnerUuid(playerId)) {
            return 0.0;
        }
        BigDecimal balance = treasuryApi.getBalanceByOwnerUuid(playerId);
        return balance != null ? balance.doubleValue() : 0.0;
    }

    @Override
    public @NotNull PaymentResult transfer(@NotNull UUID fromId, @NotNull UUID toId,
                                            double amount, @NotNull String ledgerMessage) {
        try {
            Account payer = resolvePayerAccount(fromId);
            Account recipient = resolveRecipientAccount(toId);
            // Treasury rejects amounts with more than 2 decimal places. Amounts
            // derived from arithmetic (e.g. pro-rata refunds: price * remaining /
            // total) can carry extra precision, so normalise to 2 decimals here.
            BigDecimal normalisedAmount = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
            treasuryApi.transfer(new TransferRequest(
                    payer.getAccountId(),
                    recipient.getAccountId(),
                    normalisedAmount,
                    ledgerMessage,
                    initiatorFor(payer, fromId),
                    null,
                    PLUGIN_SYSTEM,
                    null
            ));
            return new PaymentResult.Success();
        } catch (Exception e) {
            return new PaymentResult.Failure(e.getMessage() != null ? e.getMessage() : "Treasury transfer failed");
        }
    }

    @Override
    public @NotNull String formatAmount(double amount) {
        return treasuryApi.formatAmount(BigDecimal.valueOf(amount));
    }

    @Override
    public boolean hasLedgerSupport() {
        return true;
    }

    /**
     * Resolves the payer's Treasury account.
     *
     * <p>A government pays from its own treasury. Resolving it as a personal account instead would
     * both charge the wrong balance and, through {@code resolveOrCreatePersonal}, open a personal
     * account against a UUID no player will ever log in as.
     */
    private @NotNull Account resolvePayerAccount(@NotNull UUID partyUuid) {
        OptionalInt governmentAccountId = governmentAccounts.accountId(partyUuid);
        if (governmentAccountId.isPresent()) {
            Account account = treasuryApi.getAccountById(governmentAccountId.getAsInt());
            if (account != null) {
                return account;
            }
        }
        return treasuryApi.resolveOrCreatePersonal(partyUuid);
    }

    /**
     * Picks the UUID recorded as the transaction's initiator.
     *
     * <p>A government's party UUID is synthetic and means nothing to Treasury, so a transfer it
     * pays for is attributed to the account's owner. The acting player would be a better record
     * still, but the economy interface deals in parties: several payments (lease expiry sweeps,
     * auction settlement) have no acting player at all.
     */
    private @NotNull UUID initiatorFor(@NotNull Account payer, @NotNull UUID fromId) {
        if (governmentAccounts.accountId(fromId).isEmpty()) {
            return fromId;
        }
        return payer.getOwnerUuid() != null ? payer.getOwnerUuid() : fromId;
    }

    /**
     * Resolves the recipient's Treasury account, preferring
     * GOVERNMENT &gt; PERSONAL &gt; BUSINESS &gt; first-available.
     * <p>
     * GOVERNMENT wins first because legacy government entities (e.g.
     * DCGovernment) are real Minecraft accounts whose UUID owns <em>both</em> a
     * personal and a government account; their leasehold income must land in
     * the government treasury, not the player's personal balance.
     * <p>
     * Ordinary landlords have no government account, so PERSONAL is chosen next:
     * rental/sale income belongs to them personally, never a firm BUSINESS
     * account they happen to own (firm accounts are owned by the proprietor's
     * own UUID, which is how such funds previously leaked into business
     * accounts).
     * <p>
     * When the recipient has no account at all, resolve-or-create their personal
     * account.
     */
    private @NotNull Account resolveRecipientAccount(@NotNull UUID ownerUuid) {
        OptionalInt governmentAccountId = governmentAccounts.accountId(ownerUuid);
        if (governmentAccountId.isPresent()) {
            Account account = treasuryApi.getAccountById(governmentAccountId.getAsInt());
            if (account != null) {
                return account;
            }
        }
        List<Account> accounts = treasuryApi.getAccountsByOwner(ownerUuid);
        if (!accounts.isEmpty()) {
            return accounts.stream()
                    .filter(a -> a.getAccountType() == AccountType.GOVERNMENT)
                    .findFirst()
                    .or(() -> accounts.stream()
                            .filter(a -> a.getAccountType() == AccountType.PERSONAL)
                            .findFirst())
                    .or(() -> accounts.stream()
                            .filter(a -> a.getAccountType() == AccountType.BUSINESS)
                            .findFirst())
                    .orElse(accounts.get(0));
        }
        return treasuryApi.resolveOrCreatePersonal(ownerUuid);
    }
}
