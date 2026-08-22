package io.github.md5sha256.realty.command.resolver;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import io.github.md5sha256.realty.Realty;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Completes {@link ModuleName} against the modules currently loaded. */
@Singleton
public final class ModuleNameResolver implements ParameterResolver<ModuleName> {

    private final ModuleLifecycleManager<Realty> modules;

    @Inject
    public ModuleNameResolver(@NotNull ModuleLifecycleManager<Realty> modules) {
        this.modules = modules;
    }

    @Override
    public @NotNull Class<ModuleName> type() {
        return ModuleName.class;
    }

    @Override
    public @NotNull Optional<ModuleName> resolve(@NotNull String token,
                                                 @NotNull CommandSender sender) {
        // Accepts an unknown name: the reload path reports "no such module" in Realty's own
        // wording, which is better than the resolver's generic invalid-argument error.
        return token.isBlank() ? Optional.empty() : Optional.of(new ModuleName(token));
    }

    @Override
    public @NotNull List<String> suggestions(@NotNull String prefix, @NotNull CommandSender sender) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return this.modules.getActiveModules().keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lowered))
                .sorted()
                .toList();
    }
}
