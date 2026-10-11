package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.core.api.view.ProcessVariablesView;
import io.github.neherim.ruslo.core.runtime.process.ProcessVariables;

import java.util.Map;
import java.util.Optional;

/**
 * Process variables read together with {@link io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails}.
 * Equality is defined by variable values.
 */
record ProcessVariablesSnapshot(ProcessVariables variables) implements ProcessVariablesView {

    @Override
    public <T> Optional<T> get(VariableType<T> type) {
        return Optional.ofNullable(variables.getVariable(type.name(), type.clazz()));
    }

    @Override
    public Map<String, String> asJsonMap() {
        return variables.asMap();
    }

    @Override
    public String toString() {
        return variables.toString();
    }
}
