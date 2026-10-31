package io.github.neherim.ruslo.spring.config;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.spring.config.property.ProcessEngineProperties;
import io.github.neherim.ruslo.spring.integration.SpringJdbcDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.time.Clock;
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
     * Returns the system clock as the engine clock. The bean is not a candidate for injection by type: other
     * {@link Clock} beans of the application do not replace it, and it is not injected into the application.
     * To replace the engine clock, declare a bean named {@code processEngineClock}.
     */
    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "processEngineClock")
    public Clock processEngineClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnMissingBean(name = "defaultWorker", value = WorkerSpec.class)
    public WorkerSpec defaultWorker() {
        return WorkerSpec.builder(WorkerId.DEFAULT)
            .transactional()
            .build();
    }

    @Bean
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

        @Bean
        public ProcessEngine processEngine(
                @Qualifier("processEngineClock") Clock processEngineClock,
                Database database,
                List<WorkerSpec> workerSpecs,
                ProcessEngineProperties props,
                ProcessEngineMetrics metrics
        ) {
            var effectiveWorkerSpecs = new ArrayList<>(workerSpecs);
            // Specs from properties go after @Bean specs, so they take precedence.
            effectiveWorkerSpecs.addAll(props.getWorkerSpecs());
            var builder = new ProcessEngineBuilder(database)
                .withClock(processEngineClock)
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
        @Bean
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
    @Bean
    @ConditionalOnMissingBean(ProcessEngineAdmin.class)
    public ProcessEngineAdmin processEngineAdmin(ProcessEngine processEngine) {
        return new ProcessEngineAdmin(processEngine);
    }

    /**
     * Returns an initializer for a {@link ProcessEngine} declared by the application: it only starts and stops
     * the engine and registers no definitions.
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessEngineInitializer processEngineInitializer(ProcessEngine processEngine) {
        return new ProcessEngineInitializer(processEngine);
    }

    /**
     * Returns no-op metrics when no other {@link ProcessEngineMetrics} bean, such as the Micrometer one, exists.
     */
    @Bean
    @ConditionalOnMissingBean
    public ProcessEngineMetrics noOpProcessEngineMetrics() {
        return new ProcessEngineMetrics() {
        };
    }
}
