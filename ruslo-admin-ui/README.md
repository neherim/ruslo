# Ruslo Admin UI

Admin web interface for Ruslo. The module is a Spring Boot auto-configuration; once explicitly enabled, it serves the UI at `/ruslo`.

The UI is built with Thymeleaf and htmx: pages are served as HTML, and interactive blocks are updated with server-rendered HTML fragments, without an SPA layer. The small API under `${base-path}/api` only loads the process graph for the diagram.

## Setup

Admin UI expects a `ProcessEngine` bean in the application. It is usually created by `ruslo-spring-boot-starter`.

A Spring MVC (servlet) application is required: the module does not pull in `spring-boot-starter-web` transitively, so that it does not change the type of a WebFlux or non-web application. Without a servlet web context the admin auto-configuration is not activated.

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-admin-ui</artifactId>
    <version>${ruslo.version}</version>
</dependency>
```

Admin UI is disabled by default. To enable it, add to `application.yml`:

```yaml
ruslo:
  admin-ui:
    enabled: true
```

## Configuration

| Property | Default | Description |
|---|---:|---|
| `ruslo.admin-ui.enabled` | `false` | Enables the admin UI auto-configuration. |
| `ruslo.admin-ui.base-path` | `/ruslo` | Base path of the HTML pages and the process diagram API. |

## Spring Security

Admin UI does not restrict access by itself, and through it one can start processes, change their state and variables, and send messages. The module does not create its own `SecurityFilterChain`: protecting it is the application's job.

All admin addresses (pages, htmx fragments, `api` and static files under `assets`) live under `base-path`, so one rule is enough:

```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/ruslo/**").hasRole("PROCESS_ADMIN")
                    .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults())
            .build();
}
```

If `base-path` is changed, use it in the rule instead of `/ruslo`. CSRF can stay enabled: pages pass the `_csrf` token in modifying htmx requests automatically.

Every modifying action is logged (`INFO`; on failure `WARN` with the stack trace) with the user name from `HttpServletRequest.getUserPrincipal()`, or `anonymous` without authentication.

## Frontend assets

Static files are served by the module itself at `${base-path}/assets/**` from `classpath:/ruslo-admin/assets/` and do not depend on the application's static resource settings (`spring.web.resources.*`, `spring.mvc.static-path-pattern`) or on `server.servlet.context-path`. The browser revalidates them on every load (`Cache-Control: no-cache`), so old versions do not stay cached after a library upgrade.

Third-party frontend dependencies are shipped locally in `assets/vendor/`, so the base screens and the process diagram need no CDN access. The diagram is drawn by [Cytoscape.js](https://js.cytoscape.org/) with the [cytoscape-dagre](https://github.com/cytoscape/cytoscape.js-dagre) layout (dagre is bundled into its build); licenses are next to the files. The admin's own code is in `assets/admin.css` and `assets/admin.js`.

## Pages

- `/ruslo`: dashboard with a summary and the registered processes.
- `/ruslo/processes`: process list with filters, process start, link to the process page.
- `/ruslo/processes/{id}`: process page with actions and the diagram.

Page features are described in [docs/admin-ui.md](../docs/admin-ui.md).

## Process diagram

The diagram is built automatically from the `ProcessDefinition`: nodes are the declared states, edges are step and await event transitions. There is nothing to draw or attach by hand.

- A node is labeled with `ProcessState.description()` (the state name by default); the name is shown in the tooltip.
- A step has a solid border, an await a dashed one, a terminal state a double one; a dot marks the initial state.
- An edge is labeled with the branching outcome, the message name (✉), `every <interval>`, `after <delay>` or `at <variable>` (⏱). An event that keeps the process waiting is drawn as a loop. The transition to `fallbackState` on error is not shown.
- The mouse wheel scrolls the page; zoom with Ctrl/⌘ held, a pinch gesture or the toolbar buttons. The legend under the diagram explains the notation.

The Diagram button on the dashboard opens the definition diagram. On the process page the diagram also highlights the visited states and transitions (from the state history) and the current state according to the status (`FAILED` in the error color). If several transitions are declared between two states, all of them are highlighted: the history does not record which event fired. An administrative move outside the declared transitions is drawn as a dashed edge. With history disabled only the current state is highlighted.

On the process page the diagram is expanded right away. If the process entered a state several times, a `×N` counter is shown under the node label; the node tooltip shows the state kind, worker, number of entries and the time of the last entry. An await event that keeps the process in the same state is not recorded in the history and does not increase the counter. A toolbar button expands the diagram to full screen.

The Change state dialog shows `ProcessState.description()` next to the state name if the description is set and differs from the name.

The diagram data is served by `GET ${base-path}/api/definitions/{type}/graph`: nodes (`id`, `label`, `kind`, `worker`) and transitions (`source`, `target`, `trigger`, `label`).

## Local development

For UI development the module has a separate dev application, `AdminUiDevApplication`, in `src/test/java/io/github/neherim/ruslo/admin/dev`.
It lets library developers check the admin UI without rebuilding an application after every HTML, CSS or JavaScript change.

The dev application is not part of the production artifact: it lives in test scope, starts an in-memory H2, registers the test processes `ORDER` and `SUPPORT_TICKET`, starts several instances of them and uses the regular admin auto-configuration.

Run from the repository root (the dev application's `mainClass` is set in the module's `pom.xml`; you can also run `main` of `AdminUiDevApplication` from the IDE):

```bash
./mvnw install -pl ruslo-admin-ui -am -DskipTests && ./mvnw spring-boot:test-run -pl ruslo-admin-ui -Dspring-boot.run.skip=false
```

After startup:

| URL | Purpose |
|---|---|
| `http://localhost:8080/ruslo` | admin UI |
| `http://localhost:8080/h2-console` | H2 console for the dev database |

In this mode templates and static files are read directly from the sources:

```text
ruslo-admin-ui/src/main/resources/templates/ruslo-admin/
ruslo-admin-ui/src/main/resources/ruslo-admin/assets/
```

After changing `.html`, `.css` or `.js`, reloading the page is enough. A restart is needed only after changes to Java code, Spring settings or the dev application's test processes.
