package io.github.md5sha256.realty.command.resolver;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player named on the command line, for the arguments that accept only a player.
 *
 * <p>Distinct from {@link io.github.md5sha256.realty.command.util.NamedAuthority} because the
 * resolver framework dispatches on parameter type: the agent commands must reject a government
 * where {@code /realty set titleholder} accepts one, and two behaviours need two types.</p>
 */
public record PlayerAuthority(@NotNull UUID uuid, @NotNull String name) {
}
