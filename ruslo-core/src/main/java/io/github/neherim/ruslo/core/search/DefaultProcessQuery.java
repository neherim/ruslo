package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.exception.TooManyRowsException;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessQuery;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class DefaultProcessQuery implements ProcessQuery {
    private final ProcessInstanceSearchDao processInstanceSearchDao;
    private final ProcessSearchCriteria criteria;
    private boolean lock;

    DefaultProcessQuery(ProcessInstanceSearchDao processInstanceSearchDao) {
        this.processInstanceSearchDao = processInstanceSearchDao;
        this.criteria = new ProcessSearchCriteria();
    }

    @Override
    public ProcessQuery withLock() {
        this.lock = true;
        return this;
    }

    @Override
    public ProcessQuery byId(UUID processId) {
        criteria.processId(processId);
        return this;
    }

    @Override
    public ProcessQuery byType(ProcessType type) {
        criteria.typeName(type.name());
        return this;
    }

    @Override
    public ProcessQuery byTypeName(String typeName) {
        criteria.typeName(typeName);
        return this;
    }

    @Override
    public ProcessQuery byState(ProcessState state) {
        criteria.stateName(state.name());
        return this;
    }

    @Override
    public ProcessQuery byStateName(String stateName) {
        criteria.stateName(stateName);
        return this;
    }

    @Override
    public ProcessQuery byStatus(ProcessStatus status) {
        criteria.status(status);
        return this;
    }

    @Override
    public ProcessQuery byAttempted() {
        criteria.markAttempted();
        return this;
    }

    @Override
    public ProcessQuery byBusinessKey(String businessKey) {
        criteria.businessKey(businessKey);
        return this;
    }

    @Override
    public ProcessQuery byCorrelationId(String correlationId) {
        criteria.correlationId(correlationId);
        return this;
    }

    @Override
    public ProcessQuery byScheduledAtFrom(Instant scheduledAtFrom) {
        criteria.scheduledAtFrom(scheduledAtFrom);
        return this;
    }

    @Override
    public ProcessQuery byScheduledAtTo(Instant scheduledAtTo) {
        criteria.scheduledAtTo(scheduledAtTo);
        return this;
    }

    @Override
    public ProcessQuery byCreatedAtFrom(Instant createdAtFrom) {
        criteria.createdAtFrom(createdAtFrom);
        return this;
    }

    @Override
    public ProcessQuery byCreatedAtTo(Instant createdAtTo) {
        criteria.createdAtTo(createdAtTo);
        return this;
    }

    @Override
    public ProcessQuery byVariablesLike(String pattern) {
        criteria.variablesLike(pattern);
        return this;
    }

    @Override
    public List<ProcessInstanceSummary> list(int limit, int offset) {
        return processInstanceSearchDao.findSummaries(criteria, limit, offset, lock);
    }

    @Override
    public List<ProcessInstanceSummary> list() {
        return processInstanceSearchDao.findSummaries(criteria, null, null, lock);
    }

    @Override
    public Optional<ProcessInstanceSummary> single() {
        var result = list();
        if (result.size() > 1) {
            throw new TooManyRowsException("Found " + result.size() + " processes by filter");
        }
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    @Override
    public Optional<ProcessInstanceDetails> singleDetails() {
        var result = processInstanceSearchDao.findDetails(criteria, lock);
        if (result.size() > 1) {
            throw new TooManyRowsException("Found " + result.size() + " processes by filter");
        }
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }
}
