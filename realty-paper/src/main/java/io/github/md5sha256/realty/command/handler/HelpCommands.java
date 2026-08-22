package io.github.md5sha256.realty.command.handler;

import com.google.inject.Inject;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.command.resolver.HelpCategory;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.Locale;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** The {@code /realty help …} family. */
@Command({"realty", "rl"})
public final class HelpCommands implements CommandHandler {

    private final Message messages;

    @Inject
    public HelpCommands(@NotNull Message messages) {
        this.messages = messages;
    }

    @Route("help")
    @Permission("realty.command.help")
    @Description("Show the Realty help index")
    public void help(@Sender CommandSender sender) {

                sender.sendMessage(this.messages.component(MessageKeys.HELP_MAIN));
    }

    @Route("help <category>")
    @Permission("realty.command.help")
    @Description("Show help for one category")
    public void helpCategory(@Sender CommandSender sender,
                             @Arg("category") HelpCategory category) {

                String requested = category.value().toLowerCase(Locale.ROOT);
        if (!ALL_CATEGORIES.contains(requested)) {
            sender.sendMessage(this.messages.component(MessageKeys.HELP_UNKNOWN_CATEGORY));
            return;
        }
        String key = switch (requested) {
            case "basics" -> MessageKeys.HELP_BASICS;
            case "management" -> MessageKeys.HELP_MANAGEMENT;
            case "offers" -> MessageKeys.HELP_OFFERS;
            case "auctions" -> MessageKeys.HELP_AUCTIONS;
            case "admin" -> MessageKeys.HELP_ADMIN;
            default -> MessageKeys.HELP_UNKNOWN_CATEGORY;
        };
        sender.sendMessage(this.messages.component(key));
    }
    private static final Set<String> ALL_CATEGORIES = Set.of("basics", "management", "offers", "auctions", "admin");
}
