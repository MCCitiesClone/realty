package io.github.md5sha256.realty.database.entity;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Internal entity record mapping to the {@code RealtyGovernmentParty} DDL table: the tie between a
 * Treasury {@code GOVERNMENT} account and the synthetic UUID that stands for it wherever Realty
 * stores a party.
 *
 * <p>A region's authority, title holder, landlord and tenant are all plain {@code UUID} columns. A
 * government has no player UUID of its own, so it is addressed by {@link #partyIdFor(int) a UUID
 * derived from its account id} — every comparison, index and uniqueness constraint in the schema
 * then keeps working without knowing governments exist. Only the edges of the system (paying,
 * authorizing, rendering a name, syncing WorldGuard) need to ask whether a party UUID names a
 * government, which is what this table answers.
 *
 * @param partyUuid   synthetic UUID stored in the contract tables in place of a player UUID
 * @param accountId   Treasury account id this party stands for
 * @param displayName the Treasury account's display name, cached for rendering when Treasury is
 *                    unreachable or the account has been archived
 */
public record GovernmentPartyEntity(
        @NotNull UUID partyUuid,
        int accountId,
        @NotNull String displayName
) {

    /**
     * Namespace prefix for {@link #partyIdFor(int)}. Distinct from Bukkit's {@code "OfflinePlayer:"}
     * offline-mode prefix so the two derivations cannot produce the same UUID for any input.
     */
    private static final String PARTY_NAMESPACE = "realty:government-account:";

    /**
     * Derives the party UUID that stands for a Treasury account.
     *
     * <p>Deterministic on purpose: re-registering an account that is already known yields the same
     * UUID, so registration is idempotent and contract rows written before a row in this table was
     * lost still resolve once it is registered again. Never generate this randomly — the contract
     * tables hold the derived value and nothing rewrites them.
     *
     * @param accountId Treasury account id
     * @return the party UUID for that account
     */
    public static @NotNull UUID partyIdFor(int accountId) {
        return UUID.nameUUIDFromBytes(
                (PARTY_NAMESPACE + accountId).getBytes(StandardCharsets.UTF_8));
    }
}
