# AGENTS.md

## Module Scope

This module provides the packaged administrative UI for Ruslo.
It is a Spring Boot auto-configuration module with server-rendered HTML pages,
htmx fragment endpoints, a JSON endpoint for process definition graphs, and static assets under
`src/main/resources/ruslo-admin/assets/`.

Root [AGENTS.md](../AGENTS.md) rules still apply. This file adds module-specific guidance for
`ruslo-admin-ui`.

## Code Style

- Code, Javadoc, comments, log and exception messages are in English, following the root code style rules.
- Keep controller and service code explicit and easy to trace. Avoid hidden global model setup unless it is already the local pattern.
- Keep user-facing admin UI text consistent with the existing templates. Do not introduce a new copy style in one screen only.

## Architecture

- `config` contains admin UI auto-configuration, configuration properties, and resource/template wiring.
- `controller` is organized by feature: page and htmx endpoints live together when they change together.
- `ProcessEngineAdminApiController` exposes only `GET ${base-path}/api/definitions/{typeName}/graph` for the diagram.
  Process reads and mutations belong to page and htmx controllers.
- Keep controller beans explicitly registered in auto-configuration; this module is a library, not an application component-scan root.
- `dto` contains UI/API transfer shapes. Keep them focused on admin UI needs and do not leak template-specific quirks into core types.
- `view` contains template view types such as `Breadcrumb`.
- `service` adapts Ruslo runtime/definition data for controllers. Put admin orchestration here instead of embedding it in templates.
- `src/main/resources/templates/ruslo-admin` contains Thymeleaf pages and fragments.
- `src/main/resources/ruslo-admin/assets` contains packaged CSS and JavaScript assets. The auto-configuration serves them at `${base-path}/assets/**`; templates reference them relative to `<base>` (`assets/...`), never by an absolute path.
- `src/test/java/.../dev` contains the local dev application and is not part of the production artifact.

## UI Rules

- Prefer server-rendered Thymeleaf and htmx over SPA-style client logic.
- Use htmx for partial updates when the server can return the required HTML directly.
- Keep custom JavaScript small and behavior-oriented. Do not add a frontend build pipeline unless explicitly requested.
- Keep htmx URLs compatible with the shared `<base>` setup: static admin-relative endpoints should stay relative where the existing templates use relative URLs.
- Mutating htmx endpoints answer a failed action with `422` and an error status line; `admin.js` swaps `422` into the target and keeps the dialog open.
- Preserve CSRF support for mutating htmx requests. Pages already expose CSRF metadata and body-level htmx headers when Spring Security provides `_csrf`.
- Keep mutation workflows in native `<dialog>` modals when changing the current modal-driven UI.
- Keep read-only process details visible on the page; use modals for state, message, and variable mutations.
- Keep every action visible across process states. Disable inapplicable actions and show a short reason.
- Give each action one place in the UI. Send message belongs in the Actions menu.
- Use the existing shared fragments for shell-level UI such as sidebar, theme bootstrap, and theme toggle.
- htmx fragments should be meaningful refresh boundaries. Avoid splitting one page into many load-on-open fragments unless there is a clear runtime reason.
- The process details page should fetch the process once during initial page render and use a single whole-details refresh endpoint for follow-up htmx updates.
- Keep the details refresh version check: polling sends `since`, and unchanged content returns `204` without a body.
  Preserve the selected tab, expanded variables and the diagram's zoom and position across refreshes. Polling skips
  refreshes while a dialog, action menu, full-screen view or text selection is active, or the page is hidden.
- When a details/action page must return to the previous filtered admin list, prefer an explicit validated
  `returnTo` parameter with an admin-relative page URL. Do not use hidden HTTP session state, cookies,
  or client storage for this navigation state.
- Keep bundled assets local.
- Do not edit minified third-party assets unless the task is specifically to update the vendored asset.

## Process Diagrams

- Derive the graph from the public process specification: state descriptions label nodes, and explicit route labels
  take precedence over outcome names. Keep graph assembly in `ProcessEngineAdminService` and rendering in `admin.js`.
- History records state entries, not the event that caused a transition. Do not infer a specific event when several
  declared transitions connect the same states; same-state await events do not add history entries.
- Keep the diagram canvas across details refreshes (`hx-preserve`) and avoid rebuilding an unchanged diagram.

## Styling

- Pico CSS is the base stylesheet. `admin.css` should layer admin-specific layout and component rules on top.
- Avoid one-off styling inside templates when a reusable class in `admin.css` is more maintainable.
- Keep the UI operational and dense: admin screens are tools for scanning, filtering, and acting on processes.
- Use compact Unicode/text icons in the existing sidebar/nav icon slots unless the task explicitly introduces another icon system.
- When changing layout, verify that text does not overlap and controls remain usable on both narrow and desktop widths.

## Configuration And Security

- Auto-configuration requires a servlet web application, a `ProcessEngine` bean and explicit enabling. The web starter
  dependency is optional; do not make this module pull Spring MVC into consuming WebFlux or non-web applications.
- Public properties live under `ruslo.admin-ui`.
- Current properties:
  - `ruslo.admin-ui.enabled` (default `false`)
  - `ruslo.admin-ui.base-path` (default `/ruslo`)
- If a public property is added or changed, update `README.md` in this module and any relevant root docs/examples.
- Do not add a module-owned `SecurityFilterChain` by default. Authentication and authorization belong to the consuming application unless explicitly requested.
- Keep the graph endpoint compatible with the diagram when changing HTML behavior.
- Mutating actions log the current principal (or `anonymous`): success at `INFO`, failure at `WARN` with the exception.

## Local Development

Run the admin UI dev application from the repository root:

```bash
./mvnw install -pl ruslo-admin-ui -am -DskipTests && ./mvnw spring-boot:test-run -pl ruslo-admin-ui -Dspring-boot.run.skip=false
```

Useful URLs after startup:

- `http://localhost:8080/ruslo`
- `http://localhost:8080/h2-console`

Templates and static assets are read from source paths:

- `ruslo-admin-ui/src/main/resources/templates/ruslo-admin/`
- `ruslo-admin-ui/src/main/resources/ruslo-admin/assets/`

HTML, CSS, and JavaScript changes should usually need only a browser refresh.
Restart the dev application after changing Java code, Spring configuration, or dev test process definitions.

## Build And Test Commands

Run commands from the repository root.

```bash
./mvnw test -pl ruslo-admin-ui -am
./mvnw test -pl ruslo-admin-ui -am -Dtest=ProcessEngineAdminUiPageControllersTest \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test -pl ruslo-admin-ui -am -Dtest=ProcessEngineAdminUiHtmxControllersTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Use `-am` when module tests require upstream modules to be built.
For selective runs with `-am`, `surefire.failIfNoSpecifiedTests=false` lets upstream modules without the selected
test pass; check that the intended admin UI tests actually ran.
For public API or starter interaction changes, run a broader reactor compile or test scope.

## Testing Guidance

- Controller tests should assert the real HTML contract for full pages, htmx fragments, and JSON endpoints.
- Graph changes also need coverage in `ProcessEngineAdminServiceGraphTest`; API response checks live in
  `ProcessEngineAdminApiControllerTest`.
- When changing htmx attributes, modal behavior, base-path handling, or shared fragments, update the matching controller/template tests in the same change.
- For visual or interaction regressions, start the dev application and verify the real admin page in a browser.
- Do not rely only on source inspection for layout bugs, htmx target errors, or browser URL-resolution issues.

## Documentation

- Keep `ruslo-admin-ui/README.md` aligned with real module behavior.
- User-facing admin UI behavior belongs in `docs/en/admin-ui.md` and `docs/ru/admin-ui.md`; keep both versions aligned.
- Describe what operators can do in the UI and keep implementation details in the module.
- Update docs when changing properties, security assumptions, local dev workflow, public URLs, or packaged assets.
- If examples need to demonstrate admin UI behavior, prefer updating `ruslo-examples` rather than adding example-only production code here.
