package io.github.md5sha256.realty.command.resolver;

import org.jetbrains.annotations.NotNull;

/**
 * The name of a loaded module jar, as {@code /realty module reload} takes it.
 *
 * <p>A distinct type so the argument keeps completing against the modules actually loaded; a plain
 * string argument has no suggestions.</p>
 */
public record ModuleName(@NotNull String value) {
}
