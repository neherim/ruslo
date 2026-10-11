package io.github.neherim.ruslo.examples.cookbook.retries;

/** The gateway rejected the recipient: a retry with the same data will not help. */
public class InvalidRecipientException extends RuntimeException {
    public InvalidRecipientException(String recipient) {
        super("Invalid recipient: " + recipient);
    }
}
