package io.github.md5sha256.realty.database.mapper;

import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Base mapper interface for CRUD operations on the {@code RealtyGovernmentParty} table.
 * SQL annotations are provided by database-specific sub-interfaces.
 *
 * @see GovernmentPartyEntity
 */
public interface GovernmentPartyMapper {

    /**
     * Registers a government party, or refreshes the cached display name if the account is already
     * registered. Idempotent: the party UUID is derived from the account id, so re-registering the
     * same account never produces a second party.
     *
     * @param partyUuid   the derived party UUID
     * @param accountId   the Treasury account id
     * @param displayName the account's display name
     * @return number of rows affected
     */
    int upsert(@NotNull UUID partyUuid, int accountId, @NotNull String displayName);

    /**
     * Selects the government party registered under a party UUID.
     *
     * @param partyUuid the party UUID stored in a contract row
     * @return the party, or {@code null} if this UUID does not name a government
     */
    @Nullable GovernmentPartyEntity selectByPartyUuid(@NotNull UUID partyUuid);

    /**
     * Selects the government party registered for a Treasury account.
     *
     * @param accountId the Treasury account id
     * @return the party, or {@code null} if the account has never been registered
     */
    @Nullable GovernmentPartyEntity selectByAccountId(int accountId);

    /**
     * Selects every registered government party, ordered by display name.
     *
     * @return all registered parties
     */
    @NotNull List<GovernmentPartyEntity> selectAll();

    /**
     * Removes a government party registration.
     *
     * <p>Contract rows still holding the party UUID are left untouched — they keep resolving to a
     * bare UUID with no name or account, which is why callers should only unregister a party that
     * holds nothing.
     *
     * @param partyUuid the party UUID to unregister
     * @return number of rows deleted (1 on success, 0 if it was not registered)
     */
    int deleteByPartyUuid(@NotNull UUID partyUuid);
}
