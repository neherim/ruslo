package io.github.neherim.ruslo.examples.cookbook.retries;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class SendNotificationStep implements ProcessStep {
    private final NotificationGateway notificationGateway;

    public SendNotificationStep(NotificationGateway notificationGateway) {
        this.notificationGateway = notificationGateway;
    }

    /** Sends the notification; a gateway exception goes to the process {@code onError}. */
    @Override
    public void execute(ProcessContext context) {
        notificationGateway.send(
                context.getVariable(NotificationDefinition.RECIPIENT),
                context.getVariable(NotificationDefinition.TEXT));
    }
}
