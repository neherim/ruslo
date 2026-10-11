package io.github.neherim.ruslo.admin.controller;

import io.github.neherim.ruslo.admin.dto.DefinitionResponse;

import java.util.List;

final class ProcessDefinitionFilterOptions {
    private ProcessDefinitionFilterOptions() {
    }

    static List<String> stateNames(List<DefinitionResponse> definitions, String typeName) {
        return definitions.stream()
                .filter(definition -> typeName == null || typeName.isBlank() || definition.type().equals(typeName))
                .flatMap(definition -> definition.states().stream())
                .map(DefinitionResponse.StateResponse::name)
                .distinct()
                .sorted()
                .toList();
    }
}
