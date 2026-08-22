package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single property-tax rule: a {@link TagMatch} predicate plus the formula
 * (a {@link io.github.md5sha256.realty.tax.TaxFormula} expression over
 * {@code <plots>}) applied to regions it matches. Rules are evaluated top-to-
 * bottom and the first match wins.
 */
@ConfigurationObject
public record TaxRule(
        @ConfigurationValue(path = "match") @Nullable TagMatch match,
        @ConfigurationValue(path = "formula") @Nullable String formula
) {

    public TaxRule {
        if (match == null) {
            match = new TagMatch(null, null);
        }
        if (formula == null || formula.isBlank()) {
            formula = "0";
        }
    }

    public @NotNull TagMatch match() {
        return match;
    }

    public @NotNull String formula() {
        return formula;
    }
}
