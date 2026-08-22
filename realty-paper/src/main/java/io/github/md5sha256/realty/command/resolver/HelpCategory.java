package io.github.md5sha256.realty.command.resolver;

import org.jetbrains.annotations.NotNull;

/**
 * A {@code /realty help} category.
 *
 * <p>A distinct type so the argument completes; a plain string argument has no suggestions, and
 * losing them would leave the categories undiscoverable.</p>
 */
public record HelpCategory(@NotNull String value) {
}
