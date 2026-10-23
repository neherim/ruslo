package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.Instant;
import java.util.UUID;

final class ProcessSearchCriteria {
    private UUID processId;
    private String typeName;
    private String stateName;
    private ProcessStatus status;
    private boolean attempted;
    private String businessKey;
    private Instant nextRunAtFrom;
    private Instant nextRunAtTo;
    private Instant createdAtFrom;
    private Instant createdAtTo;
    private String correlationId;
    private String variablesLike;

    UUID processId() {
        return processId;
    }

    void processId(UUID processId) {
        this.processId = processId;
    }

    String typeName() {
        return typeName;
    }

    void typeName(String typeName) {
        this.typeName = typeName;
    }

    String stateName() {
        return stateName;
    }

    void stateName(String stateName) {
        this.stateName = stateName;
    }

    ProcessStatus status() {
        return status;
    }

    void status(ProcessStatus status) {
        this.status = status;
    }

    boolean attempted() {
        return attempted;
    }

    void markAttempted() {
        this.attempted = true;
    }

    String businessKey() {
        return businessKey;
    }

    void businessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    Instant nextRunAtFrom() {
        return nextRunAtFrom;
    }

    void nextRunAtFrom(Instant nextRunAtFrom) {
        this.nextRunAtFrom = nextRunAtFrom;
    }

    Instant nextRunAtTo() {
        return nextRunAtTo;
    }

    void nextRunAtTo(Instant nextRunAtTo) {
        this.nextRunAtTo = nextRunAtTo;
    }

    Instant createdAtFrom() {
        return createdAtFrom;
    }

    void createdAtFrom(Instant createdAtFrom) {
        this.createdAtFrom = createdAtFrom;
    }

    Instant createdAtTo() {
        return createdAtTo;
    }

    void createdAtTo(Instant createdAtTo) {
        this.createdAtTo = createdAtTo;
    }

    String correlationId() {
        return correlationId;
    }

    void correlationId(String correlationId) {
        this.correlationId = correlationId;
    }

    String variablesLike() {
        return variablesLike;
    }

    void variablesLike(String variablesLike) {
        this.variablesLike = variablesLike;
    }
}
