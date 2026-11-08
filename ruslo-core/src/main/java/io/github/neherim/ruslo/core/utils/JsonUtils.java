package io.github.neherim.ruslo.core.utils;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import static tools.jackson.core.StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN;

public class JsonUtils {
    public static final ObjectMapper DEFAULT_OBJECT_MAPPER = JsonMapper.builder()
        .configure(WRITE_BIGDECIMAL_AS_PLAIN, true)
        .build();

    /** Creates a reusable reader that preserves decimal precision and scale. */
    public static ObjectReader readerFor(ObjectMapper mapper, TypeReference<?> type) {
        return mapper.readerFor(type)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }
}
