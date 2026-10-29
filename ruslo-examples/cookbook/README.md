# Ruslo Cookbook

Recipes: small examples for common tasks that you can copy into your project.
Each recipe is a package in `src/main/java/io/github/neherim/ruslo/examples/cookbook/` with a test next to it
in `src/test/java`. Start reading a recipe with its `*Definition` class (the "Start with" column below): its Javadoc
explains the task, how the recipe works and what can go wrong, and links the other classes of the recipe.

Shared configuration (H2 and workers) is in [`src/main/resources/application.yml`](src/main/resources/application.yml),
the application tables used by the recipes are in [`src/main/resources/schema.sql`](src/main/resources/schema.sql).

## How recipes are written

- The `*Definition` Javadoc has three parts: the task, how the recipe solves it, and at most three pitfalls you hit
  while solving this task. Engine details are in `docs/*.md`.
- A recipe shows how to implement one pattern with the engine. Code that does not serve it is not there: steps log
  instead of doing real work, and variables and messages exist only if the recipe uses them.
- A step or handler that is the point of the recipe is a separate class. A small one is a method of the definition
  referenced from `define`: static when it needs nothing (`SeatReservationDefinition::reserveSeat`), an instance
  method when it calls a bean (`this::send`). `define` has no inline lambdas.
- An external service is a stub that logs calls. Errors and declines come from input data (an amount over 1000, an
  address in Antarctica), the same in tests and in the demo.
- Tests show the scenarios of the pattern through process state and variables: no Mockito and no emulated failures.

## Recipes

| Package | Start with | Task | Documentation |
|---|---|---|---|
| [`branching`](src/main/java/io/github/neherim/ruslo/examples/cookbook/branching) | [`LoanApplicationDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/branching/LoanApplicationDefinition.java) | Check an application: approve, reject or send to manual review | [`docs/concepts.md`](../../docs/concepts.md) |
| [`retries`](src/main/java/io/github/neherim/ruslo/examples/cookbook/retries) | [`NotificationDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/retries/NotificationDefinition.java) | External service temporarily unavailable: retries with growing delay, a give-up state, no retries for unrecoverable errors | [`docs/error-handling.md`](../../docs/error-handling.md) |
| [`failandresume`](src/main/java/io/github/neherim/ruslo/examples/cookbook/failandresume) | [`InvoiceDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/failandresume/InvoiceDefinition.java) | Unrecoverable error: stop the process, find it, fix the data and continue | [`docs/error-handling.md`](../../docs/error-handling.md) |
| [`transactionalstart`](src/main/java/io/github/neherim/ruslo/examples/cookbook/transactionalstart) | [`PurchaseOrderDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/transactionalstart/PurchaseOrderDefinition.java) | Save an order and start its process in one transaction | [`docs/execution.md`](../../docs/execution.md) |
| [`deadline`](src/main/java/io/github/neherim/ruslo/examples/cookbook/deadline) | [`SeatReservationDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/deadline/SeatReservationDefinition.java) | Wait for payment until a deadline that can be extended, otherwise release the reservation | [`docs/concepts.md`](../../docs/concepts.md) |
| [`webhook`](src/main/java/io/github/neherim/ruslo/examples/cookbook/webhook) | [`IdentityVerificationDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/webhook/IdentityVerificationDefinition.java) | Accept an external service callback: find the process by business key, deliver the message, ignore a repeated callback | [`docs/guarantees.md`](../../docs/guarantees.md) |
| [`fanout`](src/main/java/io/github/neherim/ruslo/examples/cookbook/fanout) | [`FileImportDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/fanout/FileImportDefinition.java) | Split work into parts, process each in its own process and wait for all of them | [`docs/concepts.md`](../../docs/concepts.md) |
| [`polling`](src/main/java/io/github/neherim/ruslo/examples/cookbook/polling) | [`ReportGenerationDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/polling/ReportGenerationDefinition.java) | Long external job: poll its status, cancel on a deadline | [`docs/concepts.md`](../../docs/concepts.md) |
| [`externalcall`](src/main/java/io/github/neherim/ruslo/examples/cookbook/externalcall) | [`CardPaymentDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/externalcall/CardPaymentDefinition.java) | Slow payment API call: a non-transactional worker with limited concurrency, no double charge on retry | [`docs/workers.md`](../../docs/workers.md), [`docs/guarantees.md`](../../docs/guarantees.md) |
| [`batch`](src/main/java/io/github/neherim/ruslo/examples/cookbook/batch) | [`BonusAccrualDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/batch/BonusAccrualDefinition.java) | Process a large table in chunks and continue where it stopped after a failure | [`docs/execution.md`](../../docs/execution.md) |
| [`approval`](src/main/java/io/github/neherim/ruslo/examples/cookbook/approval) | [`ExpenseApprovalDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/approval/ExpenseApprovalDefinition.java) | Human approval: on a deadline escalate to the next approver and restart the wait | [`docs/concepts.md`](../../docs/concepts.md) |
| [`subscription`](src/main/java/io/github/neherim/ruslo/examples/cookbook/subscription) | [`SubscriptionDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/subscription/SubscriptionDefinition.java) | Long-lived process: monthly charge on the sign-up day until the subscription is cancelled or the bank declines | [`docs/concepts.md`](../../docs/concepts.md) |
| [`cancellation`](src/main/java/io/github/neherim/ruslo/examples/cookbook/cancellation) | [`OrderDeliveryDefinition`](src/main/java/io/github/neherim/ruslo/examples/cookbook/cancellation/OrderDeliveryDefinition.java) | Cancel a long process on user request at any stage, including while the process is executing a step | [`docs/execution.md`](../../docs/execution.md) |

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
  e.g. `{"approved":true,"comment":"ok"}`.
- `NOTIFICATION`: the notification to a recipient without `@` went to the give-up state `DELIVERY_FAILED` at once,
  the one to `unavailable.example.com` after three retries.
- `INVOICE` in status `FAILED`: change the `country` variable to `"DE"` and click Retry current step.
- `IDENTITY_VERIFICATION`: send `verificationCallback`,
  e.g. `{"applicationId":"application-501","eventId":"e1","status":"DOCUMENTS_REQUIRED"}`. Send it again: the
  repeat is ignored, `handledEvents` still holds one id. Then send
  `{"applicationId":"application-501","eventId":"e2","status":"APPROVED"}`.
- `FILE_IMPORT`: find the `FILE_IMPORT_PART` parts by the parent's correlation id.
- `SEAT_RESERVATION`: after 15 minutes an unpaid reservation is released by `at`; send `extendPaymentDue`, e.g.
  `"2030-01-01T00:00:00Z"`, or set the `paymentDue` timer on the Timers tab to move the deadline.
- `REPORT_GENERATION`: the status is polled every 2 minutes; the report is ready by the third check.
- `CARD_PAYMENT`: the charge runs on `io-worker`; one payment is approved, one amount over 1000 is declined by the bank.
- `BONUS_ACCRUAL`: the state history shows the `ACCRUING` step repeating chunk by chunk.
- `EXPENSE_APPROVAL`: send the `decision` message, e.g. `{"approver":"alice","approved":true}`.
- `SUBSCRIPTION`: send the `cancel` message, e.g. `"Too expensive"`. `subscription-402` has a declined card: after
  a month it goes to `SUSPENDED`.
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
