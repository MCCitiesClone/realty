package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

@ConfigurationComponent(file = "taxes.yml")
public record TaxSettings(
        @ConfigurationValue(path = "enabled") boolean enabled,
        @ConfigurationValue(path = "government-account") @NotNull String governmentAccount,
        @ConfigurationValue(path = "exempt-uuids") @NotNull List<UUID> exemptUuids,
        @ConfigurationValue(path = "exempt-plot-threshold") int exemptPlotThreshold,
        @ConfigurationValue(path = "rules") @NotNull List<TaxRule> rules,
        @ConfigurationValue(path = "default-formula") @NotNull String defaultFormula
) {

    /** Built-in default — the Taxation Act's federal property-tax formula. Gives an
     * owner's total daily tax as a function of their plot count {@code <plots>};
     * evaluated once per owner (not per plot). Owners of 7 or fewer plots are exempt
     * via {@code exempt-plot-threshold}, and the result is rounded down to the cent. */
    public static final String DEFAULT_FORMULA = "0.25 * 1.16^<plots> + 0.3 * <plots>^2 + 2.5 * <plots> - 25";

    public TaxSettings {
        if (governmentAccount == null || governmentAccount.isBlank()) {
            governmentAccount = "DCGovernment";
        }
        if (exemptUuids == null) {
            exemptUuids = List.of();
        }
        if (rules == null) {
            rules = List.of();
        }
        if (defaultFormula == null || defaultFormula.isBlank()) {
            defaultFormula = DEFAULT_FORMULA;
        }
    }
}
