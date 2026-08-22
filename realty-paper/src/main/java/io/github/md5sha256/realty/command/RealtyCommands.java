package io.github.md5sha256.realty.command;

import io.github.md5sha256.realty.command.handler.AddCommand;
import io.github.md5sha256.realty.command.handler.AgentCommands;
import io.github.md5sha256.realty.command.handler.BuyCommand;
import io.github.md5sha256.realty.command.handler.DeleteCommand;
import io.github.md5sha256.realty.command.handler.ExtendCommand;
import io.github.md5sha256.realty.command.handler.HistoryCommand;
import io.github.md5sha256.realty.command.handler.InfoCommand;
import io.github.md5sha256.realty.command.handler.ReloadCommand;
import io.github.md5sha256.realty.command.handler.RemoveCommand;
import io.github.md5sha256.realty.command.handler.RentCommand;
import io.github.md5sha256.realty.command.handler.RentableCommand;
import io.github.md5sha256.realty.command.handler.TeleportCommand;
import io.github.md5sha256.realty.command.handler.TransferCommand;
import io.github.md5sha256.realty.command.handler.UnrentCommand;
import io.github.md5sha256.realty.command.handler.VersionCommand;
import io.github.md5sha256.realty.command.resolver.DurationResolver;
import io.github.md5sha256.realty.command.resolver.MemberNameResolver;
import io.github.md5sha256.realty.command.resolver.NamedAuthorityResolver;
import io.github.md5sha256.realty.command.resolver.PlayerAuthorityResolver;
import io.github.md5sha256.realty.command.resolver.WorldGuardRegionResolver;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The command tier's registry: every handler and argument resolver the plugin registers.
 *
 * <p>One list rather than an argument list at the call site, because the registration test builds
 * the same tree the plugin does. A handler reachable at runtime but absent here would register on
 * a server and be validated by nothing.</p>
 */
public final class RealtyCommands {

    /** Every {@code @Command} handler class, in no particular order — the framework sorts them. */
    public static final @NotNull List<Class<? extends CommandHandler>> HANDLERS = List.of(
            AddCommand.class,
            AgentCommands.class,
            BuyCommand.class,
            DeleteCommand.class,
            ExtendCommand.class,
            HistoryCommand.class,
            InfoCommand.class,
            ReloadCommand.class,
            RemoveCommand.class,
            RentCommand.class,
            RentableCommand.class,
            TeleportCommand.class,
            TransferCommand.class,
            UnrentCommand.class,
            VersionCommand.class);

    /** Every custom argument resolver. Enums need none — the framework synthesises those. */
    public static final @NotNull List<Class<? extends ParameterResolver<?>>> RESOLVERS = List.of(
            WorldGuardRegionResolver.class,
            NamedAuthorityResolver.class,
            PlayerAuthorityResolver.class,
            MemberNameResolver.class,
            DurationResolver.class);

    private RealtyCommands() {
    }
}
