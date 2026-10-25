package io.github.neherim.ruslo.examples.cookbook.demo;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.examples.cookbook.approval.ExpenseApprovalDefinition;
import io.github.neherim.ruslo.examples.cookbook.batch.BonusAccrualDefinition;
import io.github.neherim.ruslo.examples.cookbook.branching.LoanApplicationDefinition;
import io.github.neherim.ruslo.examples.cookbook.cancellation.OrderDeliveryDefinition;
import io.github.neherim.ruslo.examples.cookbook.deadline.SeatReservationDefinition;
import io.github.neherim.ruslo.examples.cookbook.externalcall.CardPaymentDefinition;
import io.github.neherim.ruslo.examples.cookbook.failandresume.InvoiceDefinition;
import io.github.neherim.ruslo.examples.cookbook.fanout.FileImportDefinition;
import io.github.neherim.ruslo.examples.cookbook.polling.ReportGenerationDefinition;
import io.github.neherim.ruslo.examples.cookbook.retries.NotificationDefinition;
import io.github.neherim.ruslo.examples.cookbook.subscription.SubscriptionDefinition;
import io.github.neherim.ruslo.examples.cookbook.transactionalstart.PurchaseOrderService;
import io.github.neherim.ruslo.examples.cookbook.webhook.IdentityVerificationDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Demo processes for the admin UI. Not a recipe: on application startup it starts processes of all recipes with data
 * that lead to different outcomes. Enabled by property {@code cookbook.demo.enabled}; disabled in tests.
 * <p>
 * What to look at in the admin UI is listed in the cookbook {@code README.md}.
 */
@Component
@ConditionalOnProperty(name = "cookbook.demo.enabled", havingValue = "true")
public class DemoProcessGenerator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoProcessGenerator.class);

    private final ProcessEngine processEngine;
    private final PurchaseOrderService purchaseOrderService;

    public DemoProcessGenerator(
            ProcessEngine processEngine,
            PurchaseOrderService purchaseOrderService
    ) {
        this.processEngine = processEngine;
        this.purchaseOrderService = purchaseOrderService;
    }

    /** Starts demo processes of all recipes. */
    @Override
    public void run(ApplicationArguments args) {
        startLoanApplications();
        startNotifications();
        startInvoices();
        startPurchaseOrders();
        startSeatReservations();
        startIdentityVerifications();
        startFileImports();
        startReportGenerations();
        startCardPayments();
        startBonusAccrual();
        startExpenseApprovals();
        startSubscriptions();
        startOrderDeliveries();
        log.info("Demo processes started, open http://localhost:8080/ruslo");
    }

    /** {@code branching}: amounts lead to approval, manual review and rejection. */
    private void startLoanApplications() {
        for (var amount : new long[]{500, 25_000, 70_000, 500_000}) {
            processEngine.newProcess(LoanApplicationDefinition.TYPE)
                .variable(LoanApplicationDefinition.AMOUNT, amount)
                .start();
        }
    }

    /** {@code retries}: a recipient without {@code @} goes to fallback at once, an unavailable gateway after retries. */
    private void startNotifications() {
        startNotification("ada@example.com");
        startNotification("grace@example.com");
        startNotification("+1-555-0100");
        startNotification("linus@unavailable.example.com");
    }

    private void startNotification(String recipient) {
        processEngine.newProcess(NotificationDefinition.TYPE)
            .variable(NotificationDefinition.RECIPIENT, recipient)
            .variable(NotificationDefinition.TEXT, "Your order has shipped")
            .start();
    }

    /** {@code failandresume}: there is no rate for country {@code XX}; the invoice stops in {@code FAILED}. */
    private void startInvoices() {
        startInvoice("order-1001", "DE");
        startInvoice("order-1002", "FR");
        startInvoice("order-1003", "XX");
    }

    private void startInvoice(String orderId, String country) {
        processEngine.newProcess(InvoiceDefinition.TYPE)
            .businessKey(orderId)
            .variable(InvoiceDefinition.COUNTRY, country)
            .start();
    }

    /** {@code transactionalstart}: the order and the process are saved in one transaction. */
    private void startPurchaseOrders() {
        purchaseOrderService.createOrder("Keyboard");
        purchaseOrderService.createOrder("Monitor");
    }

    /** {@code deadline}: reservations wait 15 minutes for payment, then are released. */
    private void startSeatReservations() {
        for (var seat : new String[]{"A-1", "A-2"}) {
            processEngine.newProcess(SeatReservationDefinition.TYPE)
                .variable(SeatReservationDefinition.SEAT, seat)
                .start();
        }
    }

    /** {@code webhook}: applications wait for a callback with the verification result. */
    private void startIdentityVerifications() {
        processEngine.newProcess(IdentityVerificationDefinition.TYPE)
            .businessKey("application-501")
            .variable(IdentityVerificationDefinition.APPLICANT_EMAIL, "ada@example.com")
            .start();
    }

    /** {@code fanout}: a file is split into three parts. */
    private void startFileImports() {
        processEngine.newProcess(FileImportDefinition.TYPE)
            .variable(FileImportDefinition.PART_COUNT, 3)
            .start();
    }

    /** {@code polling}: the report service stub has the report ready by the third status check. */
    private void startReportGenerations() {
        for (var period : new String[]{"2026-08", "2026-09"}) {
            processEngine.newProcess(ReportGenerationDefinition.TYPE)
                .variable(ReportGenerationDefinition.PERIOD, period)
                .start();
        }
    }

    /** {@code externalcall}: the charge runs on {@code io-worker}; the bank declines amounts over 1000. */
    private void startCardPayments() {
        for (var amount : new String[]{"50.00", "5000.00"}) {
            processEngine.newProcess(CardPaymentDefinition.TYPE)
                .variable(CardPaymentDefinition.AMOUNT, new BigDecimal(amount))
                .start();
        }
    }

    /** {@code batch}: a thousand accounts are processed in batches of 100. */
    private void startBonusAccrual() {
        processEngine.newProcess(BonusAccrualDefinition.TYPE)
            .variable(BonusAccrualDefinition.LAST_ACCOUNT_ID, 1000L)
            .start();
    }

    /** {@code approval}: the report waits for alice's decision; without one it goes to the manager after 2 days. */
    private void startExpenseApprovals() {
        processEngine.newProcess(ExpenseApprovalDefinition.TYPE)
            .businessKey("expense-301")
            .variable(ExpenseApprovalDefinition.APPROVER, "alice")
            .start();
    }

    /** {@code subscription}: the first period is paid; the next charge is in a month, the second card is declined. */
    private void startSubscriptions() {
        startSubscription("subscription-401", "tok_visa");
        startSubscription("subscription-402", "tok_declined");
    }

    private void startSubscription(String subscriptionId, String cardToken) {
        var subscribedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var paidUntil = SubscriptionDefinition.nextChargeAt(subscribedAt, subscribedAt);
        processEngine.newProcess(SubscriptionDefinition.TYPE)
            .businessKey(subscriptionId)
            .variable(SubscriptionDefinition.CARD_TOKEN, cardToken)
            .variable(SubscriptionDefinition.SUBSCRIBED_AT, subscribedAt)
            .variable(SubscriptionDefinition.PAID_UNTIL, paidUntil)
            .timer(SubscriptionDefinition.NEXT_CHARGE, paidUntil)
            .start();
    }

    /** {@code cancellation}: orders wait for payment and can be cancelled until handover to the courier. */
    private void startOrderDeliveries() {
        for (var orderId : new String[]{"order-3001", "order-3002"}) {
            processEngine.newProcess(OrderDeliveryDefinition.TYPE)
                .businessKey(orderId)
                .start();
        }
    }
}
