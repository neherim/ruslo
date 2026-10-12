package io.github.neherim.ruslo.admin.dev;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves admin UI templates and static files from the sources, so edits show up without a rebuild.
 */
@Configuration
public class AdminUiDevResourcesConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/ruslo");
    }

    private static final Path ADMIN_UI_RESOURCES = resolveAdminUiResources();

    /**
     * Returns a template resolver that reads HTML from {@code src/main/resources}.
     */
    @Bean
    public ITemplateResolver adminUiDevTemplateResolver() {
        var resolver = new FileTemplateResolver();
        resolver.setPrefix(ADMIN_UI_RESOURCES.resolve("templates").toString() + "/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        resolver.setCheckExistence(true);
        resolver.setOrder(0);
        return resolver;
    }

    /**
     * Serves static files from {@code src/main/resources}.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ruslo/assets/**")
            .addResourceLocations(ADMIN_UI_RESOURCES.resolve("ruslo-admin/assets/").toUri().toString())
            .setCachePeriod(0);
    }

    private static Path resolveAdminUiResources() {
        var moduleDir = Path.of("src/main/resources").toAbsolutePath().normalize();
        if (isAdminUiResources(moduleDir)) {
            return moduleDir;
        }

        var reactorDir = Path.of("ruslo-admin-ui/src/main/resources").toAbsolutePath().normalize();
        if (isAdminUiResources(reactorDir)) {
            return reactorDir;
        }

        throw new IllegalStateException("Admin UI resources directory not found");
    }

    private static boolean isAdminUiResources(Path path) {
        return Files.exists(path.resolve("templates/ruslo-admin/dashboard/page.html"))
                && Files.exists(path.resolve("ruslo-admin/assets/admin.js"));
    }
}
