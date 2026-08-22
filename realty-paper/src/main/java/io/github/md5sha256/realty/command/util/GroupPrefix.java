package io.github.md5sha256.realty.command.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The prefix marking a WorldGuard group rather than a player in {@code /realty add} and
 * {@code /realty remove}.
 *
 * <p>WorldGuard spells this {@code g:name}, and so did Realty — but a colon cannot be typed in a
 * Cloud command argument. Cloud maps these onto {@code StringArgumentType.word()}, whose reader
 * accepts only {@code 0-9 A-Z a-z _ - . +}, so the client rejects the command before sending it.
 * WorldGuard gets away with the colon because its own commands are legacy Bukkit commands, which
 * reach the server as one unparsed string.
 *
 * <p>So {@code g.name} is what is offered and displayed, and {@code g:name} is still accepted for
 * console and scripts, which are parsed server-side where the colon never mattered.
 */
public final class GroupPrefix {

    /** Typeable in game; the form shown to players. */
    public static final String GROUP_PREFIX = "g.";

    /** Accepted but never shown — WorldGuard's spelling, still valid from console. */
    public static final String LEGACY_GROUP_PREFIX = "g:";

    private GroupPrefix() {
    }

    /**
     * Returns the group name a token refers to, or {@code null} if it names a player.
     *
     * @param token the raw argument
     * @return the group name without its prefix, or {@code null}
     */
    public static @Nullable String groupNameIfPrefixed(@NotNull String token) {
        for (String prefix : new String[]{GROUP_PREFIX, LEGACY_GROUP_PREFIX}) {
            if (token.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }
}
