package io.github.md5sha256.realty.command.resolver;

import org.jetbrains.annotations.NotNull;

/**
 * A region tag id, as the {@code /realty tag …} commands take it.
 *
 * <p>A distinct type so the argument completes against the tags configured in
 * {@code region-tags.yml}; a plain string argument has no suggestions.</p>
 */
public record TagId(@NotNull String value) {
}
