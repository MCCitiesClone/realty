package io.github.md5sha256.realty.party;

import io.github.md5sha256.realty.api.PartyAuthorizer;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.AccountType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the UUID stored in a region's authority, title-holder, landlord or tenant column to the
 * party it names — an ordinary player, or a Treasury {@code GOVERNMENT} account standing in for one.
 *
 * <p>This is the only place in the plugin that knows a party might not be a player. Everything that
 * treats a party as one — paying it, authorizing an action as it, rendering its name, granting it
 * build rights — goes through here, so the rest of the code keeps handling plain UUIDs.
 *
 * <h2>Caching</h2>
 * Registered governments are held in memory: an authorization check happens on nearly every
 * command, and it must not cost a database round trip. The map holds <em>every</em> registered
 * party, so a UUID that is absent from it is definitively a player and needs no lookup. Access
 * decisions are never cached — Treasury membership can change at any moment and a stale
 * {@code true} would be a privilege leak.
 *
 * <p>A government registered by another process (for example the web API) is invisible until
 * {@link #refresh()} runs, which {@code /realty reload} does.
 *
 * <h2>Without Treasury</h2>
 * On a Vault-only server governments cannot be authorized or paid. Already-registered parties still
 * render by their cached display name, but {@link #actsFor} refuses them rather than guessing, and
 * no new government can be registered.
 */
public final class PartyService implements RegionParties {

    private final Supplier<RealtyBackend> backend;
    private final @Nullable TreasuryApi treasuryApi;
    private final Function<UUID, CompletableFuture<String>> playerNameResolver;
    private final Logger logger;

    /** Every registered government party, keyed by the UUID the contract tables store. */
    private final Map<UUID, GovernmentPartyEntity> byPartyUuid = new ConcurrentHashMap<>();

    /**
     * @param backend            supplier of the backend; a supplier rather than the instance
     *                           because the backend takes this service as its
     *                           {@link PartyAuthorizer}, so one of the two must be resolved lazily
     * @param treasuryApi        Treasury, or {@code null} on a Vault-only server
     * @param playerNameResolver resolves an ordinary player UUID to a username
     * @param logger             plugin logger
     */
    public PartyService(@NotNull Supplier<RealtyBackend> backend,
                        @Nullable TreasuryApi treasuryApi,
                        @NotNull Function<UUID, CompletableFuture<String>> playerNameResolver,
                        @NotNull Logger logger) {
        this.backend = backend;
        this.treasuryApi = treasuryApi;
        this.playerNameResolver = playerNameResolver;
        this.logger = logger;
    }

    /**
     * Reloads the government party cache from the database. Safe to call at any time; on failure
     * the previous contents are kept rather than dropped, since an empty cache would silently
     * demote every government to an unrecognised UUID.
     */
    public void refresh() {
        try {
            List<GovernmentPartyEntity> parties = backend.get().getGovernmentParties();
            Map<UUID, GovernmentPartyEntity> reloaded = new ConcurrentHashMap<>();
            for (GovernmentPartyEntity party : parties) {
                reloaded.put(party.partyUuid(), party);
            }
            byPartyUuid.keySet().retainAll(reloaded.keySet());
            byPartyUuid.putAll(reloaded);
        } catch (Exception ex) {
            logger.log(Level.WARNING,
                    "Failed to reload government parties; keeping the previously loaded set", ex);
        }
    }

    /**
     * Returns whether Treasury is available. Government parties can only be registered, authorized
     * and paid when it is.
     */
    public boolean treasuryAvailable() {
        return treasuryApi != null;
    }

    // --- Resolution ---

    /**
     * Returns the government a party UUID names, or empty if it is an ordinary player UUID.
     */
    public @NotNull Optional<GovernmentPartyEntity> government(@Nullable UUID partyUuid) {
        return partyUuid == null ? Optional.empty() : Optional.ofNullable(byPartyUuid.get(partyUuid));
    }

    /**
     * Returns whether a party UUID names a government rather than a player.
     */
    public boolean isGovernment(@Nullable UUID partyUuid) {
        return partyUuid != null && byPartyUuid.containsKey(partyUuid);
    }

    /**
     * Returns the Treasury account id a party UUID stands for, or empty for a player.
     */
    public @NotNull OptionalInt accountId(@Nullable UUID partyUuid) {
        GovernmentPartyEntity party = partyUuid == null ? null : byPartyUuid.get(partyUuid);
        return party == null ? OptionalInt.empty() : OptionalInt.of(party.accountId());
    }

    /**
     * Returns every registered government party, ordered by display name.
     */
    public @NotNull List<GovernmentPartyEntity> parties() {
        List<GovernmentPartyEntity> parties = new ArrayList<>(byPartyUuid.values());
        parties.sort(Comparator.comparing(GovernmentPartyEntity::displayName, String.CASE_INSENSITIVE_ORDER));
        return parties;
    }

    /**
     * Returns the registered party whose display name matches, ignoring case and whitespace.
     *
     * <p>Whitespace is ignored because a command argument is a single token: a government called
     * "Springfield City" has to be reachable as {@code gov:SpringfieldCity}, which is also the form
     * tab-completion offers. The name as typed still matches, so quoting is never required.
     *
     * <p>Matches against the cache only — use {@link #registerByName(String)} to reach a government
     * account that Realty has not been told about yet.
     */
    public @NotNull Optional<GovernmentPartyEntity> partyByName(@NotNull String displayName) {
        String needle = squash(displayName);
        return byPartyUuid.values().stream()
                .filter(party -> squash(party.displayName()).equals(needle))
                .findFirst();
    }

    /**
     * Normalises a government name for matching: lower-cased with all whitespace removed.
     */
    private static @NotNull String squash(@NotNull String name) {
        return name.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Returns the single-token form of a government's name, as {@code gov:<Name>} accepts it and
     * tab-completion offers it.
     */
    public static @NotNull String commandName(@NotNull GovernmentPartyEntity party) {
        return party.displayName().replaceAll("\\s+", "");
    }

    /**
     * Looks a Treasury {@code GOVERNMENT} account up by display name and registers it as a party,
     * returning the party that may then hold a region.
     *
     * <p>Registration is idempotent and refreshes the cached display name, so calling this for an
     * already-known government simply returns it with its current name.
     *
     * @param displayName the government account's display name
     * @return the registered party, or empty if Treasury is unavailable or has no such account
     */
    public @NotNull Optional<GovernmentPartyEntity> registerByName(@NotNull String displayName) {
        if (treasuryApi == null) {
            return Optional.empty();
        }
        Account account;
        try {
            account = treasuryApi.getGovernmentAccountByName(displayName);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Treasury lookup failed for government account " + displayName, ex);
            return Optional.empty();
        }
        if (account == null || account.getAccountType() != AccountType.GOVERNMENT) {
            return Optional.empty();
        }
        String name = account.getDisplayName() != null ? account.getDisplayName() : displayName;
        GovernmentPartyEntity party = backend.get().registerGovernmentParty(account.getAccountId(), name);
        byPartyUuid.put(party.partyUuid(), party);
        return Optional.of(party);
    }

    /**
     * Unregisters a government, so it can no longer be named by {@code gov:<Name>}.
     *
     * <p>Regions it already holds keep the party UUID and keep resolving through the database, but
     * the party disappears from suggestions and from {@link #partyByName(String)}.
     *
     * @param partyUuid the party to unregister
     * @return {@code true} if it was registered
     */
    public boolean unregister(@NotNull UUID partyUuid) {
        boolean removed = backend.get().deleteGovernmentParty(partyUuid) > 0;
        byPartyUuid.remove(partyUuid);
        return removed;
    }

    // --- Authorization ---

    /**
     * {@inheritDoc}
     *
     * <p>A player party is only itself. A government party is anyone Treasury reports as able to
     * access the account — its owner, members and authorizers — so government membership stays
     * managed in Treasury and Realty never keeps a second copy of it.
     *
     * <p>Fails closed: if Treasury is missing or the lookup throws, the actor is refused rather
     * than assumed to be entitled.
     */
    @Override
    public boolean actsFor(@NotNull UUID actorId, @NotNull UUID partyId) {
        GovernmentPartyEntity party = byPartyUuid.get(partyId);
        if (party == null) {
            return actorId.equals(partyId);
        }
        if (treasuryApi == null) {
            return false;
        }
        try {
            return treasuryApi.canAccessAccount(actorId, party.accountId());
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Treasury access check failed for government account "
                    + party.displayName() + "; refusing the action", ex);
            return false;
        }
    }

    /**
     * Returns every party an actor may act as: the actor themselves, followed by each registered
     * government they can access.
     *
     * <p>Used where a query is keyed by party — a title holder's offer inbox, say — so that a
     * government's business is visible to the people who run it rather than to nobody. Costs one
     * Treasury access check per registered government, so call it off the main thread.
     *
     * @param actorId the acting player
     * @return the actor and the governments they may act for
     */
    public @NotNull List<UUID> partiesFor(@NotNull UUID actorId) {
        List<UUID> parties = new ArrayList<>();
        parties.add(actorId);
        for (GovernmentPartyEntity party : byPartyUuid.values()) {
            if (actsFor(actorId, party.partyUuid())) {
                parties.add(party.partyUuid());
            }
        }
        return parties;
    }

    // --- Display ---

    /**
     * Resolves a party UUID to the name to show players: the government's display name, or the
     * player's username.
     */
    public @NotNull CompletableFuture<String> displayName(@NotNull UUID partyUuid) {
        GovernmentPartyEntity party = byPartyUuid.get(partyUuid);
        if (party != null) {
            return CompletableFuture.completedFuture(party.displayName());
        }
        return playerNameResolver.apply(partyUuid);
    }

    /**
     * Government display name for a party UUID, or empty if it names a player. For call sites that
     * must render synchronously and already have a player-name fallback of their own.
     */
    public @NotNull Optional<String> governmentName(@Nullable UUID partyUuid) {
        return government(partyUuid).map(GovernmentPartyEntity::displayName);
    }

    // --- WorldGuard ---

    /**
     * Returns the players who should hold a region on a party's behalf: the party itself when it is
     * a player, or the government account's owner, members and authorizers.
     *
     * <p>This mirrors {@link #actsFor} — whoever can act for a government in Realty can also build
     * on what it holds. It is a snapshot: membership changed in Treasury afterwards is not
     * reflected on the region until the party is set again.
     *
     * @param partyUuid the party holding the region
     * @return the UUIDs to place in the WorldGuard owner domain, never empty for a player party
     */
    @Override
    public @NotNull Set<UUID> domainMembers(@NotNull UUID partyUuid) {
        GovernmentPartyEntity party = byPartyUuid.get(partyUuid);
        if (party == null) {
            return Set.of(partyUuid);
        }
        if (treasuryApi == null) {
            return Set.of();
        }
        // LinkedHashSet: the account owner is listed first and duplicates between the member and
        // authorizer lists collapse.
        Set<UUID> members = new LinkedHashSet<>();
        try {
            Account account = treasuryApi.getAccountById(party.accountId());
            if (account != null && account.getOwnerUuid() != null) {
                members.add(account.getOwnerUuid());
            }
            for (AccountMember member : treasuryApi.getMembers(party.accountId())) {
                if (member.getMemberUuid() != null) {
                    members.add(member.getMemberUuid());
                }
            }
            for (AccountMember authorizer : treasuryApi.getAuthorizers(party.accountId())) {
                if (authorizer.getMemberUuid() != null) {
                    members.add(authorizer.getMemberUuid());
                }
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to read the membership of government account "
                    + party.displayName() + "; its region will have no owners", ex);
        }
        return members;
    }
}
