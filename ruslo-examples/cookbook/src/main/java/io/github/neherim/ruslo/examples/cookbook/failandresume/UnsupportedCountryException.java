package io.github.neherim.ruslo.examples.cookbook.failandresume;

/** The country has no tax rules: a retry with the same data will not help. */
public class UnsupportedCountryException extends RuntimeException {
    public UnsupportedCountryException(String country) {
        super("Unsupported country: " + country);
    }
}
