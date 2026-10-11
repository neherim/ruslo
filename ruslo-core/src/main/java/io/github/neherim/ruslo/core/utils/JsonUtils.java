package io.github.neherim.ruslo.core.utils;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static tools.jackson.core.StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN;


public class JsonUtils {
    public static final ObjectMapper DEFAULT_OBJECT_MAPPER = JsonMapper.builder()
            .configure(WRITE_BIGDECIMAL_AS_PLAIN, true)
            .build();
}
