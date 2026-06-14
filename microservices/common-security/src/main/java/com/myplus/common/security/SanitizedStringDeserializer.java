package com.myplus.common.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Jackson deserializer that runs incoming JSON string values through {@link XssSanitizer}.
 *
 * Applied per-field (via {@link SafeText}) to plain-text DTO properties that are later rendered
 * (e.g. customer name). This is the JSON-body counterpart to the request-parameter
 * {@link XssRequestWrapper}; it is intentionally NOT global, so HTML-bearing fields (email/SMS
 * templates) and credentials are unaffected.
 */
public class SanitizedStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return XssSanitizer.sanitize(p.getValueAsString());
    }
}
