package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.core.utils.JsonUtils;
import tools.jackson.core.JacksonException;

import java.util.LinkedHashMap;
import java.util.Map;

final class HtmxResponseSupport {
    static final String PROCESS_LIST_CHANGED = "processListChanged";
    static final String PROCESS_DETAILS_CHANGED = "processDetailsChanged";

    private HtmxResponseSupport() {
    }

    static String errorMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /**
     * Returns an HX-Trigger value that shows a toast: {@code {"toast":{"kind":"...","text":"..."}}}.
     */
    static String buildToastTrigger(String kind, String text) {
        return writeTrigger(Map.of("toast", toast(kind, text)));
    }

    /**
     * Returns an HX-Trigger value that fires a refresh event and shows a toast.
     */
    static String buildEventAndToastTrigger(String event, String kind, String text) {
        if (event == null || event.isBlank()) {
            return buildToastTrigger(kind, text);
        }
        var trigger = new LinkedHashMap<String, Object>();
        trigger.put(event, "");
        trigger.put("toast", toast(kind, text));
        return writeTrigger(trigger);
    }

    private static Map<String, String> toast(String kind, String text) {
        return Map.of(
                "kind", kind,
                "text", text == null ? "" : text);
    }

    private static String writeTrigger(Map<String, ?> trigger) {
        try {
            return JsonUtils.DEFAULT_OBJECT_MAPPER.writeValueAsString(trigger);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not serialize htmx trigger", ex);
        }
    }
}
