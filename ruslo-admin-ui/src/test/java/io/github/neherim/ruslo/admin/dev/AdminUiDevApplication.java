package io.github.neherim.ruslo.admin.dev;

import io.github.neherim.ruslo.admin.dev.process.OrderProcessDefinition;
import io.github.neherim.ruslo.admin.dev.process.SupportTicketProcessDefinition;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Application for local development of the admin UI.
 */
@SpringBootApplication
public class AdminUiDevApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminUiDevApplication.class, args);
    }

    /**
     * Starts demo processes after application startup.
     */
    @Bean
    public ApplicationRunner adminUiDevData(ProcessEngine processEngine) {
        return args -> {
            processEngine.newProcess(OrderProcessDefinition.TYPE)
                .businessKey("order-1001")
                .variable("customer", Map.of("name", "Ada Lovelace", "tier", "gold"))
                .variable("amount", 12990)
                .start();

            processEngine.newProcess(OrderProcessDefinition.TYPE)
                .businessKey("order-1002")
                .variable("customer", Map.of("name", "Grace Hopper", "tier", "standard"))
                .variable("reason", "Manual approval required")
                .variable("error", true)
                .start();

            processEngine.newProcess(SupportTicketProcessDefinition.TYPE)
                .businessKey("ticket-2001")
                .variable("requester", Map.of("name", "Katherine Johnson", "plan", "enterprise"))
                .variable("priority", "high")
                .start();
        };
    }
}
