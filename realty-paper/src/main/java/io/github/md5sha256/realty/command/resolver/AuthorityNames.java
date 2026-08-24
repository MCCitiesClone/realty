package io.github.md5sha256.realty.command.resolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared naming rules for the authority resolvers: how a sender asks for a government explicitly
 * rather than a player who happens to share its name.
 */
public final class AuthorityNames {

    /** Prefix that forces a token to be read as a government name. */
    public static final String GOVERNMENT_PREFIX = "gov.";

    /** Older spelling of {@link #GOVERNMENT_PREFIX}, still accepted so existing macros keep working. */
    public static final String LEGACY_GOVERNMENT_PREFIX = "gov:";

    private AuthorityNames() {
    }

    /**
     * The government name a token explicitly asks for, or {@code null} when it carries no
     * government prefix and should be resolved as a player first.
     */
    public static @Nullable String governmentNameIfPrefixed(@NotNull String token) {
        for (String prefix : new String[]{GOVERNMENT_PREFIX, LEGACY_GOVERNMENT_PREFIX}) {
            if (token.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }
}
