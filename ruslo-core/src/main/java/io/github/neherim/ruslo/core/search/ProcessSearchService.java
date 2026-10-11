package io.github.neherim.ruslo.core.search;

import io.github.neherim.ruslo.core.api.view.ProcessQuery;
import io.github.neherim.ruslo.core.spi.Database;
import tools.jackson.databind.ObjectMapper;

/** Creates process search queries. */
public class ProcessSearchService {
    private final ProcessInstanceSearchDao processInstanceSearchDao;

    public ProcessSearchService(Database database, ObjectMapper objectMapper) {
        this.processInstanceSearchDao = new ProcessInstanceSearchDao(database, objectMapper);
    }

    /** Returns a new process search query. */
    public ProcessQuery processes() {
        return new DefaultProcessQuery(processInstanceSearchDao);
    }

}
