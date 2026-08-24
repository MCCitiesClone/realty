package io.github.md5sha256.realty.localisation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Converts Realty's historical {@code messages.yml} into the {@code messages.properties} the
 * framework's {@code Message} bean reads.
 *
 * <p>Two things change. Nested YAML keys flatten to the dotted keys a properties file uses, and
 * runtime placeholders move from MiniMessage's {@code <name>} tag syntax to the framework's
 * {@code {name}} syntax.</p>
 *
 * <h2>Why only some tags convert</h2>
 * <p>{@code messages.yml} spells placeholders and formatting identically — {@code <region>} and
 * {@code <red>} are both angle-bracket tags — so a blanket rewrite would turn every colour and
 * style tag into an unresolvable placeholder and strip the file of its formatting. Worse, the help
 * text documents command syntax literally: {@code /realty auction <bidduration> <paymentduration>
 * <minbid> <minbidstep>} must keep rendering those angle brackets to the player, and converting
 * them would replace real usage text with empty placeholders.</p>
 *
 * <p>So only {@link #PLACEHOLDERS} converts — the exact set of names the plugin supplies at
 * runtime, whether through a {@code TagResolver} or through the raw substitution used for values
 * that sit inside a MiniMessage tag argument. Everything else, formatting and usage text alike,
 * passes through untouched.</p>
 */
public final class MessagesYamlConverter {

    /**
     * Every placeholder name Realty fills in at runtime.
     *
     * <p>Derived from the call sites, not from the message file: the union of the names passed to
     * {@code Placeholder.unparsed/component/parsed} and the names substituted textually for
     * placeholders occupying a MiniMessage tag argument ({@code command}, {@code label},
     * {@code type} and friends), which no resolver can fill.</p>
     *
     * <p>A name absent from the message file is a harmless no-op. A name <em>missing</em> from this
     * set is not: its placeholder would stay {@code <name>}, and MiniMessage would render the tag
     * literally to the player instead of the value.</p>
     */
    public static final Set<String> PLACEHOLDERS = Set.of(
            "account", "account_id", "actor", "agent", "amount", "auctioneer", "author",
            "authority", "balance", "bidding_end_date", "buyer", "changes", "charged", "command",
            "count", "current", "date", "deadline", "display", "duration", "end_date", "error",
            "extensions", "has_auction", "highest_bid_amount", "highest_bid_player", "label",
            "landlord", "last_sold_price", "maxextensions", "members", "min_bid", "min_step",
            "module", "next", "owed", "page", "parent", "player", "plots", "prefix", "previous",
            "price", "refund", "region", "reloadable", "remaining", "sibling", "start_date",
            "state", "status", "tag", "tags", "target", "tenant", "time", "time_left",
            "title_holder", "titleholder", "total", "type", "uuid", "value", "volume", "world",
            "x", "y", "z");

    /**
     * The shared prefix moves into the framework's global placeholder palette, where
     * {@code placeholder.<name>} entries are resolved as trusted markup — which is what the
     * gradient in Realty's prefix needs.
     */
    static final String PREFIX_KEY = "prefix";
    static final String PREFIX_PROPERTY = "placeholder.prefix";

    private static final Pattern TAG = Pattern.compile("<([a-zA-Z0-9_]+)>");

    private MessagesYamlConverter() {
    }

    /**
     * Converts an operator's {@code messages.yml} to {@code messages.properties} on first run
     * after the upgrade, then renames the original aside.
     *
     * <p>Must run before the framework's message bean initialises: that bean writes a fresh
     * default {@code messages.properties} when it finds none, and an operator who had customised
     * their messages would silently get the stock text back.</p>
     *
     * <p>Does nothing once the properties file exists, so a later hand-edit is never overwritten,
     * and nothing on a fresh install, where there is no YAML to carry over.</p>
     *
     * @return {@code true} if a conversion was performed
     */
    public static boolean migrateIfNeeded(@NotNull Path dataFolder, @NotNull Logger logger) {
        Path yaml = dataFolder.resolve("messages.yml");
        Path properties = dataFolder.resolve("messages.properties");
        if (!Files.isRegularFile(yaml) || Files.exists(properties)) {
            return false;
        }
        try {
            YamlConfiguration loaded = YamlConfiguration.loadConfiguration(yaml.toFile());
            Files.writeString(properties, convert(loaded), StandardCharsets.UTF_8);
            Path archived = dataFolder.resolve("messages.yml.migrated");
            Files.move(yaml, archived, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Converted messages.yml to messages.properties, preserving your edits."
                    + " The original is kept as messages.yml.migrated.");
            return true;
        } catch (IOException | RuntimeException ex) {
            // Leave the YAML in place: the bundled default will be written instead, which is
            // recoverable, whereas a half-written properties file would not be.
            logger.warning("Could not convert messages.yml (" + ex.getMessage()
                    + "); the bundled default messages will be used instead.");
            return false;
        }
    }

    /**
     * Converts a parsed {@code messages.yml} into properties-file text, preserving the order the
     * operator's file declares so a converted file still reads like the one they edited.
     */
    public static @NotNull String convert(@NotNull ConfigurationSection yaml) {
        StringBuilder out = new StringBuilder();
        out.append("# Converted from messages.yml.\n")
                .append("# Placeholders use {name}; MiniMessage formatting is unchanged.\n\n");
        appendSection(out, yaml, "");
        return out.toString();
    }

    private static void appendSection(StringBuilder out, ConfigurationSection section, String path) {
        for (String key : section.getKeys(false)) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                appendSection(out, child, childPath);
                continue;
            }
            Object value = section.get(key);
            if (value == null) {
                continue;
            }
            String text = value instanceof List<?> lines
                    ? joinLines(lines)
                    : String.valueOf(value);
            out.append(propertyKey(childPath))
                    .append('=')
                    .append(escapeValue(convertPlaceholders(text)))
                    .append('\n');
        }
    }

    /**
     * A list-valued message is several lines of one message — a help page, an info block. The
     * previous loader joined them with newlines before handing the result to MiniMessage, so the
     * converted property holds the same joined string.
     */
    private static String joinLines(List<?> lines) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(String.valueOf(lines.get(i)));
        }
        return joined.toString();
    }

    private static String propertyKey(String path) {
        return PREFIX_KEY.equals(path) ? PREFIX_PROPERTY : path;
    }

    /** Rewrites {@code <name>} to <code>{name}</code>, but only for names the plugin fills in. */
    static @NotNull String convertPlaceholders(@NotNull String text) {
        Matcher matcher = TAG.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = PLACEHOLDERS.contains(name) ? "{" + name + "}" : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Escapes a value for a properties file: backslashes, newlines, and a leading space that
     * {@code Properties.load} would otherwise swallow. Braces are left alone — the framework only
     * treats doubled braces as literals, and no Realty message contains one.
     */
    static @NotNull String escapeValue(@NotNull String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c == ' ' && i == 0) {
                        out.append("\\ ");
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * The keys a converted file defines, for callers that need to check coverage without writing
     * anything — used by the test that keeps {@code MessageKeys} and the bundle in step.
     */
    static @NotNull Map<String, String> flatten(@NotNull ConfigurationSection yaml) {
        Map<String, String> flat = new LinkedHashMap<>();
        collect(yaml, "", flat);
        return flat;
    }

    private static void collect(ConfigurationSection section, String path, Map<String, String> into) {
        for (String key : section.getKeys(false)) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                collect(child, childPath, into);
                continue;
            }
            Object value = section.get(key);
            if (value == null) {
                continue;
            }
            String text = value instanceof List<?> lines ? joinLines(lines) : String.valueOf(value);
            into.put(propertyKey(childPath), convertPlaceholders(text));
        }
    }
}
