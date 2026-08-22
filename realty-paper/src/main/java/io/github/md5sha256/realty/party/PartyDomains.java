package io.github.md5sha256.realty.party;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Applies a party to a region's WorldGuard domains.
 *
 * <p>A player party puts one UUID in the domain, exactly as before. A government party puts in
 * everyone entitled to act for it, so whoever can manage the region in Realty can also build on it.
 * The expansion is a snapshot taken when the party is applied: membership changed in Treasury
 * afterwards reaches the region the next time the holder is set.
 */
public final class PartyDomains {

    private PartyDomains() {
    }

    /**
     * Clears the owner domain and installs the party as its sole holder.
     *
     * @param region     the region to update
     * @param parties    resolves the party's holders
     * @param partyUuid  the party taking ownership
     */
    public static void setOwners(@NotNull ProtectedRegion region,
                                 @NotNull RegionParties parties,
                                 @NotNull UUID partyUuid) {
        region.getOwners().clear();
        addOwners(region, parties, partyUuid);
    }

    /**
     * Adds the party's holders to the owner domain, leaving anyone already there in place.
     */
    public static void addOwners(@NotNull ProtectedRegion region,
                                 @NotNull RegionParties parties,
                                 @NotNull UUID partyUuid) {
        for (UUID holder : parties.domainMembers(partyUuid)) {
            region.getOwners().addPlayer(holder);
        }
    }

    /**
     * Adds the party's holders to the member domain.
     */
    public static void addMembers(@NotNull ProtectedRegion region,
                                  @NotNull RegionParties parties,
                                  @NotNull UUID partyUuid) {
        for (UUID holder : parties.domainMembers(partyUuid)) {
            region.getMembers().addPlayer(holder);
        }
    }

    /**
     * Removes the party's holders from the owner domain.
     *
     * <p>Used where a region keeps other owners and only this party is leaving — a government's
     * synthetic UUID is never in the domain itself, so removing it by UUID would leave its members
     * behind with build rights they no longer have.
     */
    public static void removeOwners(@NotNull ProtectedRegion region,
                                    @NotNull RegionParties parties,
                                    @NotNull UUID partyUuid) {
        for (UUID holder : parties.domainMembers(partyUuid)) {
            region.getOwners().removePlayer(holder);
        }
    }
}
