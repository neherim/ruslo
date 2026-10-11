package io.github.neherim.ruslo.examples.cookbook.demo;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.examples.cookbook.approval.ExpenseApprovalDefinition;
import io.github.neherim.ruslo.examples.cookbook.batch.BonusAccrualDefinition;
import io.github.neherim.ruslo.examples.cookbook.batch.LoyaltyAccountRepository;
import io.github.neherim.ruslo.examples.cookbook.branching.LoanApplicationDefinition;
import io.github.neherim.ruslo.examples.cookbook.cancellation.OrderDeliveryDefinition;
import io.github.neherim.ruslo.examples.cookbook.deadline.SeatReservationDefinition;
import io.github.neherim.ruslo.examples.cookbook.externalcall.CardPaymentDefinition;
import io.github.neherim.ruslo.examples.cookbook.failandresume.InvoiceDefinition;
import io.github.neherim.ruslo.examples.cookbook.fanout.FileImportDefinition;
import io.github.neherim.ruslo.examples.cookbook.ioworker.DocumentExportDefinition;
import io.github.neherim.ruslo.examples.cookbook.polling.ReportGenerationDefinition;
import io.github.neherim.ruslo.examples.cookbook.retries.NotificationDefinition;
import io.github.neherim.ruslo.examples.cookbook.saga.OrderFulfillmentDefinition;
import io.github.neherim.ruslo.examples.cookbook.subscription.SubscriptionDefinition;
import io.github.neherim.ruslo.examples.cookbook.testing.ReviewDefinition;
import io.github.neherim.ruslo.examples.cookbook.transactionalstart.PurchaseOrderService;
import io.github.neherim.ruslo.examples.cookbook.webhook.IdentityVerificationDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.LongStream;

/**
 * Demo processes for the admin UI. Not a recipe: on application startup it starts processes of all recipes with data
 * that lead to different outcomes. Enabled by property {@code cookbook.demo.enabled}; disabled in tests.
 * <p>
 * What to look at in the admin UI ({@code /ruslo}) after startup:
 * <ul>
 *   <li>{@code LOAN_APPLICATION}: applications in all three branches; the one in {@code MANUAL_REVIEW} waits for
 *       message {@code reviewDecision}, for example {@code {"reviewer":"alice","approved":true,"comment":"ok"}}.</li>
 *   <li>{@code INVOICE}: the invoice with a wrong country is stopped in {@code FAILED}. Change variable
 *       {@code country} to {@code "DE"} and press Retry current step.</li>
 *   <li>{@code NOTIFICATION}: the notification with a wrong recipient went to the fallback state.</li>
 *   <li>{@code FILE_IMPORT}: a parent with {@code FILE_IMPORT_PART} parts (shared correlation id) and an empty
 *       file.</li>
 *   <li>{@code SEAT_RESERVATION}, {@code IDENTITY_VERIFICATION}, {@code REVIEW}: awaits with a deadline that accept
 *       a message until it expires.</li>
 *   <li>{@code REPORT_GENERATION}: report status polling; the report is ready in a few minutes.</li>
 *   <li>{@code CARD_PAYMENT}: a payment and a bank decline.</li>
 *   <li>{@code BONUS_ACCRUAL}: a pass over a thousand accounts in batches; the state history shows repeated
 *       {@code ACCRUING}.</li>
 *   <li>{@code ORDER_FULFILLMENT}: a fulfilled order and two cancelled ones: a bank decline and a delivery failure
 *       after retries.</li>
 *   <li>{@code EXPENSE_APPROVAL}: waiting for a decision; send message {@code decision}, for example
 *       {@code {"approver":"alice","approved":true,"comment":"ok"}}.</li>
 *   <li>{@code SUBSCRIPTION}: subscriptions wait for the next charge; send message {@code cancel}, for example
 *       {@code "Too expensive"}.</li>
 *   <li>{@code ORDER_DELIVERY}: orders wait for payment; send {@code cancel} or {@code paymentReceived}, then
 *       {@code cancel} or {@code handedToCourier}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "cookbook.demo.enabled", havingValue = "true")
public class DemoProcessGenerator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoProcessGenerator.class);

    private final ProcessEngine processEngine;
    private final PurchaseOrderService purchaseOrderService;
    private final LoyaltyAccountRepository loyaltyAccountRepository;

    public DemoProcessGenerator(ProcessEngine processEngine,
                                PurchaseOrderService purchaseOrderService,
                                LoyaltyAccountRepository loyaltyAccountRepository) {
        this.processEngine = processEngine;
        this.purchaseOrderService = purchaseOrderService;
        this.loyaltyAccountRepository = loyaltyAccountRepository;
    }

    /** Starts demo processes of all recipes. */
    @Override
    public void run(ApplicationArguments args) {
        startLoanApplications();
        startNotifications();
        startInvoices();
        startDocumentExports();
        startPurchaseOrders();
        startSeatReservations();
        startIdentityVerifications();
        startFileImports();
        startReviews();
        startReportGenerations();
        startCardPayments();
        startBonusAccrual();
        startOrderFulfillments();
        startExpenseApprovals();
        startSubscriptions();
        startOrderDeliveries();
        log.info("Demo processes started, open http://localhost:8080/ruslo");
    }

    /** {@code branching}: amounts lead to approval, manual review and rejection. */
    private void startLoanApplications() {
        startLoanApplication("Ada Lovelace", 500L);
        startLoanApplication("Grace Hopper", 25_000L);
        startLoanApplication("Alan Turing", 70_000L);
        startLoanApplication("Charles Babbage", 500_000L);
    }

    private void startLoanApplication(String applicant, long amount) {
        processEngine.newProcess(LoanApplicationDefinition.TYPE)
                .variable(LoanApplicationDefinition.APPLICANT, applicant)
                .variable(LoanApplicationDefinition.AMOUNT, amount)
                .start();
    }

    /** {@code retries}: the gateway rejects a recipient without {@code @}; the notification goes to fallback. */
    private void startNotifications() {
        startNotification("ada@example.com");
        startNotification("grace@example.com");
        startNotification("+1-555-0100");
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
                .variable(InvoiceDefinition.AMOUNT, new BigDecimal("100.00"))
                .variable(InvoiceDefinition.COUNTRY, country)
                .start();
    }

    /** {@code ioworker}: rendering runs on {@code io-worker}. */
    private void startDocumentExports() {
        for (var documentId : new String[] {"contract-7", "invoice-42"}) {
            processEngine.newProcess(DocumentExportDefinition.TYPE)
                    .variable(DocumentExportDefinition.DOCUMENT_ID, documentId)
                    .start();
        }
    }

    /** {@code transactionalstart}: the order and the process are saved in one transaction. */
    private void startPurchaseOrders() {
        purchaseOrderService.createOrder("Keyboard", 2);
        purchaseOrderService.createOrder("Monitor", 1);
    }

    /** {@code deadline}: reservations wait 15 minutes for payment, then are released. */
    private void startSeatReservations() {
        for (var seat : new String[] {"A-1", "A-2"}) {
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

    /** {@code fanout}: a 250-row file is split into three parts. */
    private void startFileImports() {
        processEngine.newProcess(FileImportDefinition.TYPE)
                .variable(FileImportDefinition.FILE_NAME, "customers.csv")
                .variable(FileImportDefinition.ROW_COUNT, 250)
                .start();
    }

    /** {@code testing}: the document waits three days for the reviewer's decision. */
    private void startReviews() {
        processEngine.newProcess(ReviewDefinition.TYPE)
                .variable(ReviewDefinition.DOCUMENT_ID, "doc-1")
                .start();
    }

    /** {@code polling}: the report service stub has the report ready by the third status check. */
    private void startReportGenerations() {
        for (var period : new String[] {"2026-08", "2026-09"}) {
            processEngine.newProcess(ReportGenerationDefinition.TYPE)
                    .variable(ReportGenerationDefinition.PERIOD, period)
                    .start();
        }
    }

    /** {@code externalcall}: the bank declines amounts over 1000. */
    private void startCardPayments() {
        for (var amount : new String[] {"50.00", "5000.00"}) {
            processEngine.newProcess(CardPaymentDefinition.TYPE)
                    .variable(CardPaymentDefinition.CARD_TOKEN, "tok_visa")
                    .variable(CardPaymentDefinition.AMOUNT, new BigDecimal(amount))
                    .start();
        }
    }

    /** {@code batch}: a thousand accounts are processed in batches of 100. */
    private void startBonusAccrual() {
        LongStream.rangeClosed(1, 1000).forEach(loyaltyAccountRepository::insert);
        processEngine.newProcess(BonusAccrualDefinition.TYPE)
                .variable(BonusAccrualDefinition.BONUS_POINTS, 10)
                .start();
    }

    /** {@code saga}: the bank declines amounts over 1000; there is no delivery to Antarctica. */
    private void startOrderFulfillments() {
        startOrderFulfillment("order-2001", "99.00", "Berlin");
        startOrderFulfillment("order-2002", "5000.00", "Paris");
        startOrderFulfillment("order-2003", "99.00", "Antarctica");
    }

    private void startOrderFulfillment(String orderId, String amount, String address) {
        processEngine.newProcess(OrderFulfillmentDefinition.TYPE)
                .businessKey(orderId)
                .variable(OrderFulfillmentDefinition.ITEM, "Keyboard")
                .variable(OrderFulfillmentDefinition.AMOUNT, new BigDecimal(amount))
                .variable(OrderFulfillmentDefinition.ADDRESS, address)
                .start();
    }

    /** {@code approval}: the report waits for alice's decision; without one it goes to the manager after 60 hours. */
    private void startExpenseApprovals() {
        processEngine.newProcess(ExpenseApprovalDefinition.TYPE)
                .businessKey("expense-301")
                .variable(ExpenseApprovalDefinition.APPROVER, "alice")
                .start();
    }

    /** {@code subscription}: the first period is paid; the next charge is in 30 days. */
    private void startSubscriptions() {
        for (var subscriptionId : new String[] {"subscription-401", "subscription-402"}) {
            processEngine.newProcess(SubscriptionDefinition.TYPE)
                    .businessKey(subscriptionId)
                    .variable(SubscriptionDefinition.CARD_TOKEN, "tok_visa")
                    .variable(SubscriptionDefinition.PRICE, new BigDecimal("9.99"))
                    .variable(SubscriptionDefinition.PAID_PERIODS, 1)
                    .start();
        }
    }

    /** {@code cancellation}: orders wait for payment and can be cancelled until handover to the courier. */
    private void startOrderDeliveries() {
        for (var orderId : new String[] {"order-3001", "order-3002"}) {
            processEngine.newProcess(OrderDeliveryDefinition.TYPE)
                    .businessKey(orderId)
                    .start();
        }
    }
}
