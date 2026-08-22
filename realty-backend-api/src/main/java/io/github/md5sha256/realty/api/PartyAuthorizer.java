package io.github.md5sha256.realty.api;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Decides whether an actor is entitled to act as a party (a region's authority, title holder,
 * landlord or tenant).
 *
 * <p>For a player party this is plain UUID equality. For a
 * {@linkplain io.github.md5sha256.realty.database.entity.GovernmentPartyEntity government party} it
 * is a question only the server can answer — the backend has no view of Treasury — so the Paper
 * layer supplies an implementation that asks Treasury whether the actor may access the account.
 *
 * <p><strong>Only for authorization.</strong> Checks that ask "is this the <em>same</em> party"
 * rather than "may this actor act as that party" must keep comparing UUIDs directly: a member of a
 * government is not the government. Making an offer on a plot your government holds title to, or
 * bidding in its auction, is a personal transaction between two distinct parties and stays
 * permitted; approving that offer on the government's behalf is an authorization check and goes
 * through here.
 */
@FunctionalInterface
public interface PartyAuthorizer {

    /**
     * Plain UUID equality — the behaviour of the plugin before governments could hold a region.
     * Used when no Treasury-backed authorizer has been supplied, and by tests.
     */
    PartyAuthorizer IDENTITY = (actorId, partyId) -> actorId.equals(partyId);

    /**
     * Returns whether {@code actorId} may act as {@code partyId}.
     *
     * @param actorId the player performing the action
     * @param partyId the party whose authority the action requires
     * @return {@code true} if the actor is that party, or is entitled to act on its behalf
     */
    boolean actsFor(@NotNull UUID actorId, @NotNull UUID partyId);

    /**
     * Null-tolerant form of {@link #actsFor(UUID, UUID)} for the nullable party columns (title
     * holder, tenant). A party that is not set can never be acted for.
     *
     * @param actorId the player performing the action
     * @param partyId the party whose authority the action requires, possibly {@code null}
     * @return {@code true} if {@code partyId} is set and the actor may act as it
     */
    default boolean actsForNullable(@NotNull UUID actorId, UUID partyId) {
        return partyId != null && actsFor(actorId, partyId);
    }
}
