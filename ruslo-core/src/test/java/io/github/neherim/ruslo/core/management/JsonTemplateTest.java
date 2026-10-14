package io.github.neherim.ruslo.core.management;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTemplateTest {
    private final JsonTemplate template = new JsonTemplate(JsonUtils.DEFAULT_OBJECT_MAPPER);

    @Test
    void fillsPropertiesWithTypedPlaceholders() {
        assertEquals("""
                {"name":"","count":0,"amount":0,"active":false,"tags":[],"codes":[],"attributes":{},\
                "status":null,"createdAt":null,"id":null,"note":null,"address":{"city":"","zip":0}}""",
                compact(template.of(Order.class)));
    }

    @Test
    void followsMapperNames() {
        assertEquals("{\"order_id\":\"\",\"renamed\":0}", compact(template.of(Renamed.class)));
        var snakeCase = new JsonTemplate(JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build());
        assertEquals("{\"zip_code\":\"\"}", compact(snakeCase.of(Snake.class)));
    }

    @Test
    void stopsAtCycles() {
        assertEquals("{\"name\":\"\",\"parent\":null}", compact(template.of(Node.class)));
    }

    @Test
    void returnsScalarPlaceholderForScalarPayload() {
        assertEquals("\"\"", template.of(String.class));
        assertEquals("0", template.of(Long.class));
        assertEquals("null", template.of(Instant.class));
        assertEquals("null", template.of(Status.class));
    }

    private static String compact(String json) {
        return json.replaceAll("\\s+", "");
    }

    enum Status {
        NEW
    }

    record Address(String city, int zip) {
    }

    record Order(
            String name,
            Integer count,
            BigDecimal amount,
            boolean active,
            List<String> tags,
            int[] codes,
            Map<String, String> attributes,
            Status status,
            Instant createdAt,
            UUID id,
            Optional<String> note,
            Address address
    ) {
    }

    record Renamed(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("renamed") long value,
            @JsonIgnore String hidden
    ) {
    }

    record Snake(String zipCode) {
    }

    record Node(String name, Node parent) {
    }
}
