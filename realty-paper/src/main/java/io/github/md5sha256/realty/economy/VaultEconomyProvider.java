package io.github.md5sha256.realty.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Economy provider backed by Vault. Used when Treasury is not present.
 * Ledger messages are discarded (Vault has no per-transaction metadata support).
 * Tax collection is not available without Treasury.
 * <p>
 * Government parties are a Treasury concept and have no Vault equivalent: their UUID belongs to no
 * player, so paying it would credit an account nobody can reach. Such transfers are refused rather
 * than silently sent into the void, and a government's balance reads as zero.
 */
public final class VaultEconomyProvider implements EconomyProvider {

    private static final String GOVERNMENT_UNSUPPORTED =
            "Government accounts require Treasury; the Vault economy cannot pay one";

    private final Economy economy;
    private final GovernmentAccountLookup governmentAccounts;

    public VaultEconomyProvider(@NotNull Economy economy) {
        this(economy, GovernmentAccountLookup.NONE);
    }

    /**
     * @param governmentAccounts used only to recognise government parties so they can be refused
     */
    public VaultEconomyProvider(@NotNull Economy economy,
                                 @NotNull GovernmentAccountLookup governmentAccounts) {
        this.economy = economy;
        this.governmentAccounts = governmentAccounts;
    }

    @Override
    public double getBalance(@NotNull UUID playerId) {
        if (governmentAccounts.accountId(playerId).isPresent()) {
            return 0.0;
        }
        return economy.getBalance(Bukkit.getOfflinePlayer(playerId));
    }

    @Override
    public @NotNull PaymentResult transfer(@NotNull UUID fromId, @NotNull UUID toId,
                                            double amount, @NotNull String ledgerMessage) {
        if (governmentAccounts.accountId(fromId).isPresent()
                || governmentAccounts.accountId(toId).isPresent()) {
            return new PaymentResult.Failure(GOVERNMENT_UNSUPPORTED);
        }
        OfflinePlayer payer = Bukkit.getOfflinePlayer(fromId);
        EconomyResponse withdraw = economy.withdrawPlayer(payer, amount);
        if (!withdraw.transactionSuccess()) {
            return new PaymentResult.Failure(withdraw.errorMessage);
        }
        OfflinePlayer recipient = Bukkit.getOfflinePlayer(toId);
        EconomyResponse deposit = economy.depositPlayer(recipient, amount);
        if (!deposit.transactionSuccess()) {
            // Rollback: return money to payer
            economy.depositPlayer(payer, amount);
            return new PaymentResult.Failure(deposit.errorMessage);
        }
        return new PaymentResult.Success();
    }

    @Override
    public @NotNull String formatAmount(double amount) {
        return economy.format(amount);
    }

    @Override
    public boolean hasLedgerSupport() {
        return false;
    }
}
