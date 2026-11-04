package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.api.view.ProcessQuery;
import io.github.neherim.ruslo.core.api.view.ProcessTypeCounts;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.spi.Database;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/** Process search queries and counts by type. */
public class ProcessSearchService {
    private final ProcessInstanceSearchDao processInstanceSearchDao;

    public ProcessSearchService(
            Database database,
            ObjectMapper objectMapper,
            ProcessDefinitionRegistry processDefinitionRegistry
    ) {
        this.processInstanceSearchDao = new ProcessInstanceSearchDao(database, objectMapper,
                processDefinitionRegistry);
    }

    /** Returns a new process search query. */
    public ProcessQuery processes() {
        return new DefaultProcessQuery(processInstanceSearchDao);
    }

    /**
     * Counts incomplete processes per type, ordered by type name.
     *
     * @param overdueAt an {@code ACTIVE} process with {@code nextRunAt} at or before this moment counts as overdue
     */
    public List<ProcessTypeCounts> countIncompleteByType(Instant overdueAt) {
        return processInstanceSearchDao.countIncompleteByType(overdueAt);
    }

}
