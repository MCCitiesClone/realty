package io.github.md5sha256.realty.party;

import io.github.md5sha256.realty.api.PartyAuthorizer;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * The two questions the region layer asks about a party: may this actor act as it, and which
 * players hold a region on its behalf.
 *
 * <p>Narrower than {@link PartyService} on purpose, so the code that manipulates WorldGuard domains
 * and authorizes lease actions depends on the questions rather than on Treasury.
 */
public interface RegionParties extends PartyAuthorizer {

    /**
     * Every party is a player and holds its own regions — the behaviour before governments existed.
     */
    RegionParties PLAYERS_ONLY = new RegionParties() {
        @Override
        public boolean actsFor(@NotNull UUID actorId, @NotNull UUID partyId) {
            return actorId.equals(partyId);
        }

        @Override
        public @NotNull Set<UUID> domainMembers(@NotNull UUID partyUuid) {
            return Set.of(partyUuid);
        }
    };

    /**
     * Returns the players who should appear in a WorldGuard domain on the party's behalf: the party
     * itself when it is a player, or a government's owner, members and authorizers.
     *
     * @param partyUuid the party holding the region
     * @return the UUIDs to place in the domain; may be empty if a government's membership cannot be
     *         read
     */
    @NotNull Set<UUID> domainMembers(@NotNull UUID partyUuid);
}
