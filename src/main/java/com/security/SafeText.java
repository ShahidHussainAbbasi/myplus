package com.security;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a plain-text DTO String field that is later rendered, so its inbound JSON value is
 * XSS-sanitized during deserialization (see {@link SanitizedStringDeserializer}). Apply only to
 * fields that must NEVER contain HTML — never to template/markup or credential fields.
 * Mirrors {@code com.myplus.common.security.SafeText}.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonDeserialize(using = SanitizedStringDeserializer.class)
public @interface SafeText {
}
