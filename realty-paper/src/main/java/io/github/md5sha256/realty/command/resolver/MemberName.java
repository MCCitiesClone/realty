package io.github.md5sha256.realty.command.resolver;

import org.jetbrains.annotations.NotNull;

/**
 * A WorldGuard member named on the command line: a player name, or a group when prefixed the way
 * {@link io.github.md5sha256.realty.command.util.GroupPrefix} expects.
 *
 * <p>A distinct type rather than a bare {@code String} so the argument keeps its online-player
 * tab-completion — a plain string argument has no suggestions — and so the value reaches the
 * handler unsanitised, since a group prefix uses punctuation the default string resolver strips.</p>
 */
public record MemberName(@NotNull String value) {
}
