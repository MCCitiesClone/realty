package io.github.md5sha256.realty;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the permissions the code checks and the permissions the plugin declares in step.
 *
 * <p>A permission Bukkit has never been told about has no default, and
 * {@code Permission.DEFAULT_PERMISSION} is {@code OP} — so an undeclared node does not fail loudly,
 * it silently restricts the command to operators. Seventeen nodes were in that state at once,
 * including the whole tenant-facing {@code /realty modify} flow, which no test could notice because
 * nothing but a running server reads {@code paper-plugin.yml}.
 *
 * <p>Reads the nodes out of the compiled classes rather than the source tree, so a node passed as a
 * bare string to a helper counts exactly like one passed to {@code .permission(...)} — the earlier
 * hand audit missed seven that way.
 */
class PermissionManifestTest {

    /** All declared nodes share this prefix, which keeps class names out of the match. */
    private static final Pattern PERMISSION =
            Pattern.compile("realty\\.command\\.[a-z0-9._]+");

    private static Set<String> permissionsReferencedInCode() throws IOException {
        Path classesDir = Path.of(
                Realty.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        Assertions.assertTrue(Files.isDirectory(classesDir),
                "expected compiled classes at " + classesDir);
        Set<String> found = new TreeSet<>();
        try (Stream<Path> classes = Files.walk(classesDir)) {
            for (Path file : classes.filter(p -> p.toString().endsWith(".class")).toList()) {
                // Constant-pool strings are plain ASCII here, so scanning the bytes finds every
                // literal without needing to parse the class format.
                String constants = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                Matcher matcher = PERMISSION.matcher(constants);
                while (matcher.find()) {
                    // Trailing '.' can be picked up from a concatenation fragment.
                    found.add(matcher.group().replaceAll("\\.+$", ""));
                }
            }
        }
        return found;
    }

    private static Set<String> permissionsDeclaredInManifest() throws IOException {
        try (InputStream stream = PermissionManifestTest.class
                .getResourceAsStream("/paper-plugin.yml")) {
            Assertions.assertNotNull(stream, "paper-plugin.yml is missing from the jar");
            YamlConfiguration root;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                root = YamlConfiguration.loadConfiguration(reader);
            }
            Set<String> declared = new TreeSet<>();
            ConfigurationSection permissions = root.getConfigurationSection("permissions");
            if (permissions == null) {
                return declared;
            }
            // Bukkit treats '.' as a path separator, so "realty.command.buy:" parses as a tree and
            // the literal key is never a direct child. Walk every path and keep the ones that
            // actually describe a permission — the intermediates ("realty", "realty.command") are
            // artefacts of that split, not declared nodes.
            for (String path : permissions.getKeys(true)) {
                ConfigurationSection node = permissions.getConfigurationSection(path);
                if (node != null && (node.contains("default") || node.contains("description"))) {
                    declared.add(path);
                }
            }
            return declared;
        }
    }

    @Test
    @DisplayName("every permission the code checks is declared in paper-plugin.yml")
    void noUndeclaredPermissions() throws IOException {
        Set<String> undeclared = new TreeSet<>(permissionsReferencedInCode());
        undeclared.removeAll(permissionsDeclaredInManifest());

        Assertions.assertEquals(List.of(), List.copyOf(undeclared),
                "these permissions are checked in code but not declared in paper-plugin.yml, which"
                        + " silently makes them operator-only");
    }

    @Test
    @DisplayName("paper-plugin.yml declares no permission the code never checks")
    void noOrphanedPermissions() throws IOException {
        Set<String> orphaned = new TreeSet<>(permissionsDeclaredInManifest());
        orphaned.removeAll(permissionsReferencedInCode());

        // A node nobody checks is either a typo of one that is checked, or a leftover promising
        // access to a command that has since been renamed.
        Assertions.assertEquals(List.of(), List.copyOf(orphaned),
                "these permissions are declared in paper-plugin.yml but never checked in code");
    }
}
