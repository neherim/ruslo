package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Part of recipe {@code fanout}: imports one part of the file. The recipe is described in
 * {@link FileImportDefinition}.
 */
@Component
public class FileImportPartDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("FILE_IMPORT_PART");
    public static final VariableType<UUID> PARENT_ID = VariableType.of("parentId", UUID.class);
    public static final VariableType<String> FILE_NAME = VariableType.of("fileName", String.class);
    public static final VariableType<Integer> FROM_ROW = VariableType.of("fromRow", Integer.class);
    public static final VariableType<Integer> TO_ROW = VariableType.of("toRow", Integer.class);

    private final ImportPartStep importPartStep;

    public FileImportPartDefinition(ImportPartStep importPartStep) {
        this.importPartStep = importPartStep;
    }

    /** Describes a part import: import the rows and report to the parent. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .initialState(State.IMPORTING)
                .step(State.IMPORTING, importPartStep, State.IMPORTED)
                .terminal(State.IMPORTED)
                .build();
    }

    public enum State implements ProcessState {
        IMPORTING,
        IMPORTED
    }
}
