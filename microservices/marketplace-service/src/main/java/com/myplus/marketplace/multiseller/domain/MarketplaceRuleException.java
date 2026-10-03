package com.myplus.marketplace.multiseller.domain;

/**
 * A marketplace business rule refused the request.
 *
 * <p>The message is written for the person on the screen (standard 8d: the server's sentence wins); the code is
 * stable for tests and for the client, which must never parse the sentence.
 */
public class MarketplaceRuleException extends RuntimeException {

    private final String code;

    public MarketplaceRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
