package io.github.neherim.ruslo.spring.definition;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Base class for a process definition that is a Spring bean and takes steps and handlers from the context with
 * {@link #bean(Class)} instead of constructor injection.
 * <p>
 * {@code define} is called when definitions are registered, after all singletons are created, so
 * {@code bean(...)} causes no early initialization or circular dependencies.
 */
public abstract class SpringProcessDefinition implements ProcessDefinition, ApplicationContextAware {
    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Returns the bean of the given type from the application context. Call it inside {@code define}.
     *
     * @throws IllegalStateException if this definition is not a Spring bean
     */
    protected <T> T bean(Class<T> type) {
        if (applicationContext == null) {
            throw new IllegalStateException("Process definition " + getClass().getName()
                    + " is not a Spring bean: application context is not set");
        }
        return applicationContext.getBean(type);
    }
}
