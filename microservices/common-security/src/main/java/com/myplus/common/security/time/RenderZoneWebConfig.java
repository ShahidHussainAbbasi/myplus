package com.myplus.common.security.time;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * TZ-1 — the ONE place a UTC {@code LocalDateTime} becomes the reader's local time, and back.
 *
 * <h3>Web layer only — deliberately</h3>
 * The zone module is added to a COPY of the application's ObjectMapper, inside a NEW Jackson converter that
 * replaces the original IN THE MVC CONVERTER LIST ONLY. The application ObjectMapper — the one the outbox, the
 * caches and every RestTemplate body use — never sees it. Registering the module globally would have converted
 * those too while a browser request was on the thread: a service would send another service local time that the
 * callee reads as UTC, and an outbox event would be stored 5 h off. The shared converter INSTANCE is not mutated
 * either (other clients may hold it); the MVC list gets its own.
 *
 * <h3>What is converted</h3>
 * {@code LocalDateTime} only, and only when {@link RenderZone} is set (a request the monolith marked). The wire
 * format is unchanged — ISO {@code yyyy-MM-ddTHH:mm:ss}, no offset — so no screen changes. {@code LocalDate}
 * (a due date, a birthday — a calendar day, not an instant) is never touched.
 */
public class RenderZoneWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof MappingJackson2HttpMessageConverter original) {
                ObjectMapper mvcMapper = original.getObjectMapper().copy().registerModule(module());
                MappingJackson2HttpMessageConverter zoned = new MappingJackson2HttpMessageConverter(mvcMapper);
                zoned.setSupportedMediaTypes(original.getSupportedMediaTypes());
                converters.set(i, zoned);
            }
        }
    }

    /** Package-visible so the unit test drives the exact module the web layer uses. */
    static SimpleModule module() {
        SimpleModule m = new SimpleModule("tz1-render-zone");
        m.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
                LocalDateTimeSerializer.INSTANCE.serialize(RenderZone.toDisplay(v), g, p);
            }
        });
        m.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext c) throws IOException {
                return RenderZone.fromDisplay(LocalDateTimeDeserializer.INSTANCE.deserialize(p, c));
            }
        });
        return m;
    }
}
