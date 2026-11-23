package io.github.neherim.ruslo.spring.config;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.RusloTimeSource;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.spring.config.property.ProcessEngineProperties;
import io.github.neherim.ruslo.spring.integration.SpringJdbcDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(
        after = {
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class
        }
)
@ConditionalOnClass({ProcessEngine.class, DataSource.class, PlatformTransactionManager.class})
@EnableConfigurationProperties({ProcessEngineProperties.class})
public class ProcessEngineAutoConfiguration {

    /**
     * Provides system time for the engine unless the application declares its own {@link RusloTimeSource}.
     */
    @Bean
    @ConditionalOnMissingBean(RusloTimeSource.class)
    public RusloTimeSource rusloTimeSource() {
        return Instant::now;
    }

    @Bean("rusloDefaultWorker")
    @ConditionalOnMissingBean(name = "rusloDefaultWorker", value = WorkerSpec.class)
    public WorkerSpec defaultWorker() {
        return WorkerSpec.builder(WorkerId.DEFAULT)
            .transactional()
            .build();
    }

    @Bean("rusloDatabase")
    @ConditionalOnMissingBean
    @ConditionalOnClass(SpringJdbcDatabase.class)
    public Database database(PlatformTransactionManager transactionManager, DataSource datasource) {
        return new SpringJdbcDatabase(transactionManager, datasource);
    }

    /**
     * Creates the engine and its {@link ProcessEngineInitializer} unless the application declares its own
     * {@link ProcessEngine}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(ProcessEngine.class)
    static class ProcessEngineConfiguration {

        @Bean("rusloProcessEngine")
        public ProcessEngine processEngine(
                RusloTimeSource timeSource,
                Database database,
                List<WorkerSpec> workerSpecs,
                ProcessEngineProperties props,
                ProcessEngineMetrics metrics
        ) {
            var effectiveWorkerSpecs = new ArrayList<>(workerSpecs);
            // Specs from properties go after @Bean specs, so they take precedence.
            effectiveWorkerSpecs.addAll(props.getWorkerSpecs());
            var builder = new ProcessEngineBuilder(database)
                .withTimeSource(timeSource)
                .withDispatcherEnabled(props.getDispatcher().isEnabled())
                .withMaxProcessDispatchInterval(props.getDispatcher().getMaxInterval())
                .withMinProcessDispatchInterval(props.getDispatcher().getMinInterval())
                .withInstanceHeartbeatInterval(props.getDispatcher().getInstanceHeartbeatInterval())
                .withInstanceTimeout(props.getDispatcher().getInstanceTimeout())
                .withDatabaseMigrationEnabled(props.getDatabaseMigration().isEnabled())
                .withCorrelationIdMdcKey(props.getLogging().getMdc().getCorrelationIdKey())
                .withProcessIdMdcKey(props.getLogging().getMdc().getProcessIdKey())
                .withProcessTypeMdcKey(props.getLogging().getMdc().getProcessTypeKey())
                .withHistoryEnabled(props.getHistory().isEnabled())
                .withMetrics(metrics);
            effectiveWorkerSpecs.forEach(builder::registerWorker);
            return builder.build();
        }

        /**
         * Returns an initializer that registers every {@link ProcessDefinition} bean in the engine and starts it.
         */
        @Bean("rusloProcessEngineInitializer")
        @ConditionalOnMissingBean
        public ProcessEngineInitializer processEngineInitializer(
                ProcessEngine processEngine,
                ObjectProvider<ProcessDefinition> processDefinitions
        ) {
            return new ProcessEngineInitializer(processEngine, processDefinitions);
        }
    }

    /**
     * Exposes the engine admin API as a bean.
     */
    @Bean("rusloProcessEngineAdmin")
    @ConditionalOnMissingBean(ProcessEngineAdmin.class)
    public ProcessEngineAdmin processEngineAdmin(ProcessEngine processEngine) {
        return new ProcessEngineAdmin(processEngine);
    }

    /**
     * Returns an initializer for a {@link ProcessEngine} declared by the application: it only starts and stops
     * the engine and registers no definitions.
     */
    @Bean("rusloProcessEngineInitializer")
    @ConditionalOnMissingBean
    public ProcessEngineInitializer processEngineInitializer(ProcessEngine processEngine) {
        return new ProcessEngineInitializer(processEngine);
    }

    /**
     * Returns no-op metrics when no other {@link ProcessEngineMetrics} bean, such as the Micrometer one, exists.
     */
    @Bean("rusloNoOpProcessEngineMetrics")
    @ConditionalOnMissingBean
    public ProcessEngineMetrics noOpProcessEngineMetrics() {
        return new ProcessEngineMetrics() {
        };
    }
}
