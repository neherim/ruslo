package io.github.neherim.ruslo.examples.cookbook.failandresume;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Tax service stub. A real application uses a rate directory or an external service here. */
@Component
public class TaxService {
    private static final Map<String, BigDecimal> VAT_RATES = Map.of(
            "DE", new BigDecimal("0.19"),
            "FR", new BigDecimal("0.20"));

    /**
     * Calculates the tax on the amount for the customer's country.
     *
     * @throws UnsupportedCountryException if there is no rate for the country
     */
    public BigDecimal calculateTax(BigDecimal amount, String country) {
        var rate = VAT_RATES.get(country);
        if (rate == null) {
            throw new UnsupportedCountryException(country);
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
