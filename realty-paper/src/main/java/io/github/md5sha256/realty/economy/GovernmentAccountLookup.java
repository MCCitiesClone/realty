package io.github.md5sha256.realty.economy;

import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Resolves a party UUID to the Treasury account id it stands for, or empty when the UUID is an
 * ordinary player.
 *
 * <p>Narrow on purpose: it is the single question the economy providers need to ask about
 * governments, so they depend on this rather than on the whole party service.
 */
@FunctionalInterface
public interface GovernmentAccountLookup {

    /** Recognises no governments — the behaviour when parties are always players. */
    GovernmentAccountLookup NONE = partyUuid -> OptionalInt.empty();

    /**
     * Returns the Treasury account id for a party UUID, or empty if it names a player.
     *
     * @param partyUuid the UUID stored in a contract's party column
     * @return the government's Treasury account id, or empty
     */
    @NotNull OptionalInt accountId(@NotNull UUID partyUuid);
}
