package com.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Jackson deserializer that runs incoming JSON string values through {@link XssSanitizer}.
 * Applied per-field via {@link SafeText} to plain-text DTO properties that are later rendered
 * (e.g. the Sell flow's customer name, posted as a JSON body to {@code /addSell}). The JSON-body
 * counterpart to {@link XssRequestWrapper}; intentionally NOT global.
 * Mirrors {@code com.myplus.common.security.SanitizedStringDeserializer}.
 */
public class SanitizedStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return XssSanitizer.sanitize(p.getValueAsString());
    }
}
