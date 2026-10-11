# Ruslo Cookbook

Recipes: small examples for common tasks that you can copy into your project.
Each recipe is a package in `src/main/java/io/github/neherim/ruslo/examples/cookbook/` with a test next to it
in `src/test/java`. The Javadoc of the `*Definition` class explains the task, how the recipe works and what can go wrong.

Shared configuration (H2 and workers) is in [`src/main/resources/application.yml`](src/main/resources/application.yml),
the application tables used by the recipes are in [`src/main/resources/schema.sql`](src/main/resources/schema.sql).

## Recipes

| Package | Task | Documentation |
|---|---|---|
| [`branching`](src/main/java/io/github/neherim/ruslo/examples/cookbook/branching) | Check an application: approve, reject or send to manual review | [`docs/concepts.md`](../../docs/concepts.md) |
| [`retries`](src/main/java/io/github/neherim/ruslo/examples/cookbook/retries) | External service temporarily unavailable: retries with growing delay, a fallback state, no retries for unrecoverable errors | [`docs/error-handling.md`](../../docs/error-handling.md) |
| [`failandresume`](src/main/java/io/github/neherim/ruslo/examples/cookbook/failandresume) | Unrecoverable error: stop the process, find it, fix the data and continue | [`docs/error-handling.md`](../../docs/error-handling.md) |
| [`ioworker`](src/main/java/io/github/neherim/ruslo/examples/cookbook/ioworker) | A custom non-transactional worker for long IO operations and limiting load on an external service | [`docs/workers.md`](../../docs/workers.md) |
| [`transactionalstart`](src/main/java/io/github/neherim/ruslo/examples/cookbook/transactionalstart) | Save an order and start its process in one transaction | [`docs/execution.md`](../../docs/execution.md) |
| [`deadline`](src/main/java/io/github/neherim/ruslo/examples/cookbook/deadline) | Wait 15 minutes for payment, otherwise release the reservation | [`docs/concepts.md`](../../docs/concepts.md) |
| [`webhook`](src/main/java/io/github/neherim/ruslo/examples/cookbook/webhook) | Accept an external service callback: find the process, deliver the message, drop a duplicate | [`docs/guarantees.md`](../../docs/guarantees.md) |
| [`fanout`](src/main/java/io/github/neherim/ruslo/examples/cookbook/fanout) | Split work into parts, process each in its own process and wait for all of them | [`docs/concepts.md`](../../docs/concepts.md) |
| [`polling`](src/main/java/io/github/neherim/ruslo/examples/cookbook/polling) | Long external job: poll its status, cancel on a deadline | [`docs/concepts.md`](../../docs/concepts.md) |
| [`externalcall`](src/main/java/io/github/neherim/ruslo/examples/cookbook/externalcall) | Call a payment API on a non-transactional worker without charging twice | [`docs/guarantees.md`](../../docs/guarantees.md) |
| [`batch`](src/main/java/io/github/neherim/ruslo/examples/cookbook/batch) | Process a large table in chunks and continue where it stopped after a failure | [`docs/execution.md`](../../docs/execution.md) |
| [`saga`](src/main/java/io/github/neherim/ruslo/examples/cookbook/saga) | Reservation, payment, delivery: undo what was done on rejection or failure | [`docs/error-handling.md`](../../docs/error-handling.md) |
| [`approval`](src/main/java/io/github/neherim/ruslo/examples/cookbook/approval) | Human approval: reminders and escalation on a deadline | [`docs/concepts.md`](../../docs/concepts.md) |
| [`subscription`](src/main/java/io/github/neherim/ruslo/examples/cookbook/subscription) | Long-lived process: monthly charge until the subscription is cancelled, grace period when the bank declines | [`docs/concepts.md`](../../docs/concepts.md) |
| [`cancellation`](src/main/java/io/github/neherim/ruslo/examples/cookbook/cancellation) | Cancel a long process on user request at any stage, including while the process is executing a step | [`docs/execution.md`](../../docs/execution.md) |
| [`testing`](src/main/java/io/github/neherim/ruslo/examples/cookbook/testing) | Testing techniques for processes, including a test with a running dispatcher | [`docs/testing.md`](../../docs/testing.md) |

## Demo

The cookbook is also a runnable application: Admin UI and metrics on live processes of all recipes.

```bash
./mvnw spring-boot:run -pl ruslo-examples/cookbook -am
```

- Admin UI: <http://localhost:8080/ruslo>
- Actuator metrics: <http://localhost:8080/actuator/metrics>

On startup the [`demo`](src/main/java/io/github/neherim/ruslo/examples/cookbook/demo) package starts processes of every
recipe with different outcomes. Things to try in Admin UI:

- `LOAN_APPLICATION` in `MANUAL_REVIEW`: send the `reviewDecision` message,
  e.g. `{"reviewer":"alice","approved":true,"comment":"ok"}`.
- `INVOICE` in status `FAILED`: change the `country` variable to `"DE"` and click Retry current step.
- `IDENTITY_VERIFICATION`: send `verificationCallback`,
  e.g. `{"applicationId":"application-501","eventId":"e1","status":"DOCUMENTS_REQUIRED"}`, then send it again
  with the same `eventId` and check that the process dropped it.
- `FILE_IMPORT`: find the `FILE_IMPORT_PART` parts by the parent's correlation id.
- `SEAT_RESERVATION`: after 15 minutes an unpaid reservation is released by `timeout`.
- `BONUS_ACCRUAL`: the state history shows the `ACCRUING` step repeating chunk by chunk.
- `ORDER_FULFILLMENT`: an order declined by the bank and an order to `Antarctica` (delivery failure after retries) are
  cancelled by compensation.
- `EXPENSE_APPROVAL`: send the `decision` message, e.g. `{"approver":"alice","approved":true,"comment":"ok"}`.
- `SUBSCRIPTION`: send the `cancel` message, e.g. `"Too expensive"`.
- `ORDER_DELIVERY`: send `cancel` (`"Changed my mind"`) while waiting for payment or the courier; after `handedToCourier`
  the order is completed and does not accept cancellation.

The generator is enabled by the `cookbook.demo.enabled` property in `application.yml`; it is disabled in tests
([`src/test/resources/config/application.yml`](src/test/resources/config/application.yml)). Recipe packages do not
depend on web, Admin UI or the `demo` package.

## Commands

```bash
./mvnw test -pl ruslo-examples/cookbook -am                     # all recipes
./mvnw test -pl ruslo-examples/cookbook -am -Dtest=RetriesTest \
    -Dsurefire.failIfNoSpecifiedTests=false                      # one recipe
```
