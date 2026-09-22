package br.com.uberhidraulica.erp.finance;

import br.com.uberhidraulica.erp.finance.application.ReceivableService;
import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import br.com.uberhidraulica.erp.support.CommercialFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0015 contra PostgreSQL real: DR-0008, recebível da OS, recebimentos, ajustes, contas a pagar,
 * idempotência, concorrência, permissões e constraints. Mock não exerceria bloqueio de linha, índice único
 * nem as FKs que impedem cobrança sem aprovação.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0015FinanceIntegrationTest {
    private static final String OWNER_EMAIL = "owner-finance@example.test";
    private static final String BOOTSTRAP_PASSWORD = "finance-bootstrap-password";
    private static final String PASSWORD = "finance-operational-password";
    private static final ZoneId WORKSHOP = ZoneId.of("America/Sao_Paulo");

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Finance");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivableService receivables;
    @Autowired TransactionTemplate transactions;

    private ApiSessions api;
    private Session owner;
    private int sequence;

    @BeforeEach
    void clean() throws Exception {
        CommercialFixtures.clean(jdbc);
        for (String table : new String[]{"inventory.stock_movement", "inventory.stock_balance", "workorder.work_order_product",
                "workorder.work_order_service", "workorder.work_order_status_history", "workorder.work_order", "crm.vehicle_ownership",
                "crm.vehicle", "crm.customer", "productcatalog.product", "finance.expense_category"})
            jdbc.update("delete from " + table);
        jdbc.update("delete from finance.payment_method where code not in ('DINHEIRO','PIX','CARTAO_DEBITO','CARTAO_CREDITO','BOLETO','TRANSFERENCIA','OUTRO')");
        jdbc.update("update finance.payment_method set active = true, name = initcap(lower(replace(code, '_', ' ')))"
                + " where code not in ('PIX')");
        jdbc.update("update finance.settings set value = '0' where key = 'DEFAULT_RECEIVABLE_DUE_DAYS'");
        jdbc.update("update inventory.settings set value = 'DISABLED' where key = 'WORK_ORDER_WRITE_OFF'");
        jdbc.update("update workorder.status_automation set enabled = true");
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
    }

    // ================================================================ DR-0008

    @Test
    void quoteItemLinksAPhysicalItemOfTheSameWorkOrderOncePerQuote() throws Exception {
        String order = openWorkOrder();
        String physical = addPhysicalItem(order, "Bomba hidráulica", "900.00");
        String otherOrder = openWorkOrder();
        String foreign = addPhysicalItem(otherOrder, "Mangueira", "50.00");

        String quote = openQuote(order);
        String draft = revision(order, quote, linkedItem(physical, "Bomba hidráulica", "900.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(draft, "$.items[0].workOrderProductId")).isEqualTo(physical);

        revision(order, quote, linkedItem(physical, "Bomba de novo", "900.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ITEM_PRODUCT_ALREADY_LINKED"));
        revision(order, quote, linkedItem(foreign, "Mangueira de outra OS", "50.00")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_PRODUCT_NOT_FOUND"));
        // Orçamento alternativo da mesma OS pode cobrar o mesmo item físico.
        revision(order, openQuote(order), linkedItem(physical, "Bomba (alternativa)", "850.00")).andExpect(status().isCreated());
        // Item de texto livre continua permitido.
        revision(order, openQuote(order), item("Mão de obra", "200.00")).andExpect(status().isCreated());

        UUID quoteId = UUID.fromString(quote);
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_item (id, quote_id, work_order_id, work_order_product_id, created_at, created_by)"
                + " values (?, ?, ?::uuid, ?::uuid, now(), ?)", UUID.randomUUID(), quoteId, otherOrder, foreign, UUID.randomUUID()))
                .hasMessageContaining("fk_quote_item_quote_work_order");
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_item (id, quote_id, work_order_id, work_order_product_id, created_at, created_by)"
                + " values (?, ?, ?::uuid, ?::uuid, now(), ?)", UUID.randomUUID(), quoteId, order, foreign, UUID.randomUUID()))
                .hasMessageContaining("fk_quote_item_work_order_product");
        assertThatThrownBy(() -> jdbc.update("insert into workshop.quote_item (id, quote_id, work_order_id, work_order_product_id, created_at, created_by)"
                + " values (?, ?, ?::uuid, ?::uuid, now(), ?)", UUID.randomUUID(), quoteId, order, physical, UUID.randomUUID()))
                .hasMessageContaining("uq_quote_item_work_order_product");
    }

    // ================================================================ geração do recebível

    @Test
    void finishingGeneratesOneOpenReceivableFromApprovedItemsOnly() throws Exception {
        String order = openWorkOrder();
        String quote = openQuote(order);
        String revision = presentedRevision(order, quote, item("Troca de retentor", "350.00") + "," + item("Pintura", "900.00")
                + "," + itemWithDiscount("Alinhamento", "120.00", "20.00"));
        decide(order, quote, revision, "Troca de retentor", "APPROVE");
        decide(order, quote, revision, "Pintura", "REJECT");
        decide(order, quote, revision, "Alinhamento", "APPROVE");

        start(order);
        finish(order, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINALIZADA"));

        String receivable = api.read(owner, "/api/finance/work-orders/" + order + "/receivable").andExpect(status().isOk())
                .andExpect(jsonPath("$.originalAmount").value(450.00))
                .andExpect(jsonPath("$.outstandingBalance").value(450.00))
                .andExpect(jsonPath("$.receivedAmount").value(0))
                .andExpect(jsonPath("$.status").value("ABERTO"))
                .andExpect(jsonPath("$.billingQuoteId").value(quote))
                .andExpect(jsonPath("$.dueDate").value(today().toString()))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(receivable, "$.lines[*].description")).containsExactly("Troca de retentor", "Alinhamento");
        assertThat(count("finance.receipt")).isZero();

        // Entregar não cria outro recebível; decisão comercial posterior não o altera.
        send(post("/api/work-orders/" + order + "/deliver"), "").andExpect(status().isOk());
        assertThat(count("finance.receivable")).isEqualTo(1);
    }

    @Test
    void billingQuoteMustBeChosenWhenSeveralAreApprovedAndMustBelongToTheOrder() throws Exception {
        String order = openWorkOrder();
        String first = approvedQuote(order, "Plano A", "1000.00");
        String second = approvedQuote(order, "Plano B", "700.00");
        String elsewhere = approvedQuote(openWorkOrder(), "Outra OS", "50.00");
        start(order);

        finish(order, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BILLING_QUOTE_SELECTION_REQUIRED"));
        finish(order, elsewhere).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BILLING_QUOTE_NOT_ELIGIBLE"));
        assertThat(orderStatus(order)).isEqualTo("EM_EXECUCAO");
        assertThat(count("finance.receivable")).isZero();

        api.read(owner, "/api/finance/work-orders/" + order + "/billing-candidates").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        finish(order, second).andExpect(status().isOk());
        api.read(owner, "/api/finance/work-orders/" + order + "/receivable").andExpect(jsonPath("$.billingQuoteId").value(second))
                .andExpect(jsonPath("$.originalAmount").value(700.00));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void finishingWithoutApprovedCommercialBasisIsRefusedAndNothingIsInvented() throws Exception {
        // Sem a regra automática, a OS com orçamento reprovado continuaria podendo entrar em execução.
        jdbc.update("update workorder.status_automation set enabled = false where event = 'QUOTE_REJECTED'");
        String order = openWorkOrder();
        String quote = openQuote(order);
        String revision = presentedRevision(order, quote, item("Retífica", "2000.00"));
        decide(order, quote, revision, "Retífica", "REJECT");
        start(order);
        finish(order, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_WITHOUT_BILLING_BASIS"));
        assertThat(orderStatus(order)).isEqualTo("EM_EXECUCAO");
        assertThat(count("finance.receivable")).isZero();
    }

    @Test
    void dueDateFollowsTheConfiguredDefaultAndCanBeChangedWithHistory() throws Exception {
        send(put("/api/finance/settings"), "{\"defaultReceivableDueDays\":10}").andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultReceivableDueDays").value(10));
        send(put("/api/finance/settings"), "{\"defaultReceivableDueDays\":366}").andExpect(status().isBadRequest());
        String receivable = finishedReceivable("300.00");
        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.dueDate").value(today().plusDays(10).toString()));

        send(put("/api/finance/receivables/" + receivable + "/due-date"), "{\"dueDate\":\"" + today().minusDays(3) + "\",\"reason\":\"Acordo\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VENCIDO"))
                .andExpect(jsonPath("$.dueDateChanges[0].previousDueDate").value(today().plusDays(10).toString()));
        send(put("/api/finance/receivables/" + receivable + "/due-date"), "{\"dueDate\":\"" + today() + "\"}").andExpect(status().isBadRequest());
    }

    // ================================================================ recebimento, estorno e ajustes

    @Test
    void receiptsArePartialIdempotentAndNeverExceedTheBalance() throws Exception {
        String receivable = finishedReceivable("500.00");
        String pix = method("PIX");

        receipt(receivable, "k-1", "200.00", pix).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.paymentMethodName").value("PIX"));
        receipt(receivable, "k-1", "200.00", pix).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true"));
        receipt(receivable, "k-1", "250.00", pix).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        receipt(receivable, "k-2", "300.01", pix).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_BALANCE"));
        receipt(receivable, "k-3", "10.00", method("DINHEIRO")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_SESSION_REQUIRED"));
        receipt(receivable, null, "10.00", pix).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        api.send(owner, post("/api/finance/receivables/" + receivable + "/receipts").header("Idempotency-Key", "k-4"),
                "{\"amount\":\"10.00\",\"paymentMethodId\":\"" + pix + "\",\"receivedOn\":\"" + today().plusDays(1) + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(count("finance.receipt")).isEqualTo(1);
        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.receivedAmount").value(200.00))
                .andExpect(jsonPath("$.outstandingBalance").value(300.00)).andExpect(jsonPath("$.status").value("PARCIAL"));
        receipt(receivable, "k-5", "300.00", method("CARTAO_CREDITO")).andExpect(status().isCreated());
        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.status").value("QUITADO"));
    }

    @Test
    void reversalIsTotalUniqueReopensTheBalanceAndCorrectionIsANewReceipt() throws Exception {
        String receivable = finishedReceivable("400.00");
        String receipt = id(receipt(receivable, "r-1", "400.00", method("PIX")).andExpect(status().isCreated()));

        reverse(receipt, "e-1", "Valor digitado errado").andExpect(status().isCreated()).andExpect(jsonPath("$.reversed").value(true));
        reverse(receipt, "e-1", "Valor digitado errado").andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true"));
        reverse(receipt, "e-2", "De novo").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECEIPT_ALREADY_REVERSED"));
        api.send(owner, post("/api/finance/receipts/" + receipt + "/reversal").header("Idempotency-Key", "e-3"), "{\"reason\":\" \"}")
                .andExpect(status().isBadRequest());

        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.outstandingBalance").value(400.00))
                .andExpect(jsonPath("$.receipts[0].reversal.reason").value("Valor digitado errado"));
        receipt(receivable, "r-2", "40.00", method("PIX")).andExpect(status().isCreated());
        assertThat(count("finance.receipt")).isEqualTo(2);
        assertThat(count("finance.receipt_reversal")).isEqualTo(1);
    }

    @Test
    void discountAndSurchargePreserveTheOriginalAndDiscountNeverExceedsTheBalance() throws Exception {
        String receivable = finishedReceivable("1000.00");
        receipt(receivable, "p-1", "900.00", method("PIX")).andExpect(status().isCreated());

        adjust(receivable, "a-1", "DISCOUNT", "100.01", "Cliente fiel").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_BALANCE"));
        adjust(receivable, "a-2", "DISCOUNT", "100.00", "").andExpect(status().isBadRequest());
        adjust(receivable, "a-3", "SURCHARGE", "30.00", "Frete do componente").andExpect(status().isCreated());
        adjust(receivable, "a-3", "SURCHARGE", "30.00", "Frete do componente").andExpect(status().isOk());
        adjust(receivable, "a-4", "DISCOUNT", "130.00", "Negociação").andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalAmount").value(1000.00)).andExpect(jsonPath("$.discountAmount").value(130.00))
                .andExpect(jsonPath("$.surchargeAmount").value(30.00)).andExpect(jsonPath("$.adjustedAmount").value(900.00))
                .andExpect(jsonPath("$.outstandingBalance").value(0)).andExpect(jsonPath("$.status").value("QUITADO"));
        assertThat(count("finance.receivable_adjustment")).isEqualTo(2);
    }

    @Test
    void concurrentReceiptsCannotExceedTheBalance() throws Exception {
        String receivable = finishedReceivable("100.00");
        String pix = method("PIX");
        var results = inParallel(2, index -> receipt(receivable, "c-" + index, "60.00", pix).andReturn().getResponse().getStatus());
        assertThat(results).containsExactlyInAnyOrder(201, 409);
        assertThat(count("finance.receipt")).isEqualTo(1);
        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.outstandingBalance").value(40.00));
    }

    @Test
    void concurrentRetriesWithTheSameKeyRecordASingleReceipt() throws Exception {
        String receivable = finishedReceivable("100.00");
        String pix = method("PIX");
        var results = inParallel(3, index -> receipt(receivable, "same-key", "25.00", pix).andReturn().getResponse().getStatus());
        assertThat(results).containsExactlyInAnyOrder(201, 200, 200);
        assertThat(count("finance.receipt")).isEqualTo(1);
    }

    @Test
    void concurrentReversalsOfTheSameReceiptReverseOnce() throws Exception {
        String receivable = finishedReceivable("100.00");
        String receipt = id(receipt(receivable, "x-1", "100.00", method("PIX")).andExpect(status().isCreated()));
        var results = inParallel(2, index -> reverse(receipt, "rev-" + index, "Duplicado").andReturn().getResponse().getStatus());
        assertThat(results).containsExactlyInAnyOrder(201, 409);
        assertThat(count("finance.receipt_reversal")).isEqualTo(1);
    }

    // ================================================================ cancelamento da OS (F-08)

    @Test
    void cancellingTheOrderOfAReceivableWithReceiptsIsRefusedAndWithoutReceiptsCancelsIt() throws Exception {
        String receivable = finishedReceivable("300.00");
        UUID order = jdbc.queryForObject("select work_order_id from finance.receivable where id = ?::uuid", UUID.class, receivable);
        String receipt = id(receipt(receivable, "f-1", "100.00", method("PIX")).andExpect(status().isCreated()));

        // DR-0012 impede cancelar OS finalizada; a regra F-08 é exercida no serviço, na transação que a OS abriria.
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> receivables.cancelForWorkOrder(order, "Cancelamento da OS")))
                .isInstanceOf(FinanceException.class).extracting("code").isEqualTo("RECEIVABLE_HAS_RECEIPTS");
        reverse(receipt, "f-2", "Devolvido ao cliente").andExpect(status().isCreated());
        transactions.executeWithoutResult(tx -> receivables.cancelForWorkOrder(order, "Cancelamento da OS"));

        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.status").value("CANCELADO"))
                .andExpect(jsonPath("$.receipts.length()").value(1));
        receipt(receivable, "f-3", "10.00", method("PIX")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECEIVABLE_CANCELLED"));
    }

    @Test
    void cancellingAnOrderWithoutReceivableStillWorks() throws Exception {
        String order = openWorkOrder();
        send(post("/api/work-orders/" + order + "/cancel"), "{\"reason\":\"Cliente desistiu\"}").andExpect(status().isOk());
        assertThat(count("finance.receivable")).isZero();
    }

    // ================================================================ contas a pagar

    @Test
    void payablesArePaidPartiallyReversedAndCancelledOnlyWithoutActivePayments() throws Exception {
        String category = id(send(post("/api/finance/expense-categories"), "{\"name\":\"Aluguel\"}").andExpect(status().isCreated()));
        send(post("/api/finance/expense-categories"), "{\"name\":\"aluguel\"}").andExpect(status().isConflict());
        String body = "{\"description\":\"Aluguel de setembro\",\"supplier\":\"Imobiliária Central\",\"categoryId\":\"" + category
                + "\",\"amount\":\"3000.00\",\"dueDate\":\"" + today().plusDays(5) + "\"}";
        String payable = id(api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "pay-1"), body).andExpect(status().isCreated()));
        api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "pay-1"), body).andExpect(status().isOk());
        api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "pay-2"),
                "{\"description\":\"Sem vencimento\",\"categoryId\":\"" + category + "\",\"amount\":\"10.00\"}").andExpect(status().isBadRequest());
        assertThat(count("finance.payable")).isEqualTo(1);

        String payment = id(payment(payable, "pp-1", "1000.00", method("TRANSFERENCIA")).andExpect(status().isCreated()));
        payment(payable, "pp-2", "2000.01", method("TRANSFERENCIA")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMOUNT_EXCEEDS_BALANCE"));
        payment(payable, "pp-3", "10.00", method("DINHEIRO")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_SESSION_REQUIRED"));
        api.read(owner, "/api/finance/payables/" + payable).andExpect(jsonPath("$.status").value("PARCIAL"))
                .andExpect(jsonPath("$.outstandingBalance").value(2000.00));

        send(post("/api/finance/payables/" + payable + "/cancel"), "{\"reason\":\"Lançado em duplicidade\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYABLE_HAS_PAYMENTS"));
        api.send(owner, post("/api/finance/payments/" + payment + "/reversal").header("Idempotency-Key", "pr-1"), "{\"reason\":\"Pago errado\"}")
                .andExpect(status().isCreated());
        send(post("/api/finance/payables/" + payable + "/cancel"), "{\"reason\":\"Lançado em duplicidade\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO")).andExpect(jsonPath("$.payments.length()").value(1));

        send(put("/api/finance/expense-categories/" + category), "{\"name\":\"Aluguel\",\"active\":false}").andExpect(status().isOk());
        api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "pay-3"), body.replace("setembro", "outubro"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPENSE_CATEGORY_INACTIVE"));
    }

    // ================================================================ formas de pagamento e fluxo de caixa

    @Test
    void paymentMethodsAreConfigurableAndReceiptsKeepTheNameUsed() throws Exception {
        String custom = id(send(post("/api/finance/payment-methods"), "{\"name\":\"Crédito da casa\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREDITO_DA_CASA")).andExpect(jsonPath("$.cashSessionRequired").value(false)));
        String receivable = finishedReceivable("100.00");
        receipt(receivable, "m-1", "10.00", custom).andExpect(status().isCreated());
        send(put("/api/finance/payment-methods/" + custom), "{\"name\":\"Vale oficina\",\"active\":false}").andExpect(status().isOk());
        receipt(receivable, "m-2", "10.00", custom).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_INACTIVE"));
        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.receipts[0].paymentMethodName").value("Crédito da casa"));
        // DINHEIRO pode ser renomeado, mas continua exigindo sessão de caixa.
        send(put("/api/finance/payment-methods/" + method("DINHEIRO")), "{\"name\":\"Espécie\",\"active\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cashSessionRequired").value(true));
    }

    @Test
    void cashFlowSeparatesRealizedFromForecastAndIgnoresReversedEntries() throws Exception {
        String receivable = finishedReceivable("1000.00");
        receipt(receivable, "cf-1", "300.00", method("PIX")).andExpect(status().isCreated());
        String reversed = id(receipt(receivable, "cf-2", "100.00", method("PIX")).andExpect(status().isCreated()));
        reverse(reversed, "cf-3", "Estorno").andExpect(status().isCreated());
        String category = id(send(post("/api/finance/expense-categories"), "{\"name\":\"Peças\"}").andExpect(status().isCreated()));
        String payable = id(api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "cf-4"),
                "{\"description\":\"Compra de peças\",\"categoryId\":\"" + category + "\",\"amount\":\"500.00\",\"dueDate\":\"" + today() + "\"}")
                .andExpect(status().isCreated()));
        payment(payable, "cf-5", "200.00", method("BOLETO")).andExpect(status().isCreated());

        api.read(owner, "/api/finance/cash-flow?from=" + today() + "&to=" + today()).andExpect(status().isOk())
                .andExpect(jsonPath("$.realized.inflows").value(300.00)).andExpect(jsonPath("$.realized.outflows").value(200.00))
                .andExpect(jsonPath("$.realized.net").value(100.00))
                .andExpect(jsonPath("$.forecast.inflows").value(700.00)).andExpect(jsonPath("$.forecast.outflows").value(300.00))
                .andExpect(jsonPath("$.forecast.net").value(400.00)).andExpect(jsonPath("$.realized.days.length()").value(1));
        api.read(owner, "/api/finance/cash-flow?from=" + today() + "&to=" + today().minusDays(1)).andExpect(status().isBadRequest());
        api.read(owner, "/api/finance/receivables?status=PARCIAL").andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].customerName").value("Cliente Financeiro"));
    }

    // ================================================================ permissões (F-13)

    @Test
    void administrativeManagerReceivesButCannotReverseAdjustPayOrConfigure() throws Exception {
        String receivable = finishedReceivable("200.00");
        Session admin = api.user(owner, "admin-finance@example.test", "GERENTE_ADMINISTRATIVO");
        Session finance = api.user(owner, "manager-finance@example.test", "GERENTE_FINANCEIRO");
        String pix = method("PIX");

        api.read(admin, "/api/finance/receivables/" + receivable).andExpect(status().isOk());
        String receipt = id(api.send(admin, post("/api/finance/receivables/" + receivable + "/receipts").header("Idempotency-Key", "adm-1"),
                "{\"amount\":\"50.00\",\"paymentMethodId\":\"" + pix + "\"}").andExpect(status().isCreated()));
        api.send(admin, post("/api/finance/receipts/" + receipt + "/reversal").header("Idempotency-Key", "adm-2"), "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden());
        api.send(admin, post("/api/finance/receivables/" + receivable + "/adjustments").header("Idempotency-Key", "adm-3"),
                "{\"type\":\"DISCOUNT\",\"amount\":\"1.00\",\"reason\":\"x\"}").andExpect(status().isForbidden());
        api.send(admin, post("/api/finance/expense-categories"), "{\"name\":\"Proibida\"}").andExpect(status().isForbidden());
        api.send(admin, put("/api/finance/settings"), "{\"defaultReceivableDueDays\":5}").andExpect(status().isForbidden());
        api.send(admin, post("/api/finance/payables").header("Idempotency-Key", "adm-4"), "{\"description\":\"x\",\"categoryId\":\""
                + UUID.randomUUID() + "\",\"amount\":\"1.00\",\"dueDate\":\"" + today() + "\"}").andExpect(status().isForbidden());

        api.send(finance, post("/api/finance/receipts/" + receipt + "/reversal").header("Idempotency-Key", "fin-1"), "{\"reason\":\"Erro\"}")
                .andExpect(status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/finance/receivables"))
                .andExpect(status().isUnauthorized());
    }

    // ================================================================ constraints do PostgreSQL

    @Test
    void postgresRefusesSecondReceivableUnapprovedLinesAndForeignQuote() throws Exception {
        String receivable = finishedReceivable("100.00");
        UUID order = jdbc.queryForObject("select work_order_id from finance.receivable where id = ?::uuid", UUID.class, receivable);
        UUID quote = jdbc.queryForObject("select billing_quote_id from finance.receivable where id = ?::uuid", UUID.class, receivable);
        assertThatThrownBy(() -> insertReceivable(UUID.randomUUID(), order, quote)).hasMessageContaining("uq_receivable_work_order");

        String otherOrder = openWorkOrder();
        UUID foreignQuote = CommercialFixtures.approvedQuote(jdbc, UUID.fromString(otherOrder), "Outra", new BigDecimal("10.00"));
        assertThatThrownBy(() -> insertReceivable(UUID.randomUUID(), UUID.fromString(otherOrder), quote))
                .hasMessageContaining("fk_receivable_billing_quote");

        // Versão rejeitada não encontra alvo na FK de decisão aprovada.
        UUID rejectedRevision = jdbc.queryForObject("select quote_item_revision_id from workshop.quote_decision where quote_id = ?", UUID.class, foreignQuote);
        jdbc.update("update workshop.quote_decision set decision_type = 'REJECT' where quote_id = ?", foreignQuote);
        UUID other = UUID.randomUUID();
        insertReceivable(other, UUID.fromString(otherOrder), foreignQuote);
        assertThatThrownBy(() -> jdbc.update("insert into finance.receivable_line (id, receivable_id, quote_id, quote_item_revision_id, decision_type,"
                + " description, quantity, unit_price, discount_amount, total_amount, display_order) values (?, ?, ?, ?, 'APPROVE', 'x', 1, 10, 0, 10, 1)",
                UUID.randomUUID(), other, foreignQuote, rejectedRevision)).hasMessageContaining("fk_receivable_line_approved_decision");
        assertThatThrownBy(() -> jdbc.update("insert into finance.receipt (id, receivable_id, amount, payment_method_id, payment_method_name,"
                + " received_on, recorded_at, recorded_by, idempotency_key) values (?, ?::uuid, 0, ?::uuid, 'PIX', current_date, now(), ?, 'z')",
                UUID.randomUUID(), receivable, method("PIX"), UUID.randomUUID())).hasMessageContaining("ck_receipt_amount");
    }

    // ================================================================ apoio

    private void insertReceivable(UUID id, UUID order, UUID quote) {
        jdbc.update("insert into finance.receivable (id, work_order_id, work_order_number, customer_id, billing_quote_id, original_amount,"
                + " issued_on, due_date, created_at) values (?, ?, 1, ?, ?, 10, current_date, current_date, now())", id, order, UUID.randomUUID(), quote);
    }

    private String finishedReceivable(String amount) throws Exception {
        String order = openWorkOrder();
        approvedQuote(order, "Serviço completo", amount);
        start(order);
        finish(order, null).andExpect(status().isOk());
        return JsonPath.read(api.read(owner, "/api/finance/work-orders/" + order + "/receivable").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** Orçamento aprovado pela API: revisão, apresentação e decisão interna, como na operação real. */
    private String approvedQuote(String order, String description, String price) throws Exception {
        String quote = openQuote(order);
        String revision = presentedRevision(order, quote, item(description, price));
        decide(order, quote, revision, description, "APPROVE");
        return quote;
    }

    private String presentedRevision(String order, String quote, String items) throws Exception {
        String draft = revision(order, quote, items).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> revisions = JsonPath.read(draft, "$.revisions[*].id");
        String revision = revisions.get(revisions.size() - 1);
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/present"), "").andExpect(status().isOk());
        return revision;
    }

    private void decide(String order, String quote, String revision, String description, String decision) throws Exception {
        String content = api.read(owner, "/api/work-orders/" + order + "/quotes/" + quote).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(content, "$.items[*].revisions[?(@.description == '" + description + "')].id");
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/decisions"),
                "{\"contactChannel\":\"PRESENCIAL\",\"authorizedBy\":\"Cliente\",\"decisions\":[{\"itemReference\":\"" + ids.get(0)
                        + "\",\"decision\":\"" + decision + "\"}]}").andExpect(status().isOk());
    }

    private String openQuote(String order) throws Exception {
        return id(send(post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isCreated()));
    }

    private ResultActions revision(String order, String quote, String items) throws Exception {
        return send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions"), "{\"items\":[" + items + "]}");
    }

    private static String item(String description, String price) {
        return "{\"description\":\"" + description + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}";
    }

    private static String itemWithDiscount(String description, String price, String discount) {
        return "{\"description\":\"" + description + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\",\"discount\":\"" + discount + "\"}";
    }

    private static String linkedItem(String workOrderProductId, String description, String price) {
        return "{\"workOrderProductId\":\"" + workOrderProductId + "\",\"description\":\"" + description
                + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}";
    }

    private String addPhysicalItem(String order, String description, String price) throws Exception {
        String product = id(send(post("/api/products"), "{\"description\":\"" + description + " " + (++sequence) + "\",\"type\":\"PART\","
                + "\"unit\":\"UNIDADE\",\"salePrice\":\"" + price + "\"}").andExpect(status().isCreated()));
        String content = send(post("/api/work-orders/" + order + "/products"), "{\"productId\":\"" + product + "\",\"quantity\":\"1\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(content, "$.products[*].id");
        return ids.get(ids.size() - 1);
    }

    private void start(String order) throws Exception {
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
    }

    private ResultActions finish(String order, String billingQuote) throws Exception {
        return send(post("/api/work-orders/" + order + "/finish"), billingQuote == null ? "" : "{\"billingQuoteId\":\"" + billingQuote + "\"}");
    }

    private String orderStatus(String order) throws Exception {
        return JsonPath.read(api.read(owner, "/api/work-orders/" + order).andReturn().getResponse().getContentAsString(), "$.status");
    }

    private ResultActions receipt(String receivable, String key, String amount, String methodId) throws Exception {
        var request = post("/api/finance/receivables/" + receivable + "/receipts");
        if (key != null) request.header("Idempotency-Key", key);
        return api.send(owner, request, "{\"amount\":\"" + amount + "\",\"paymentMethodId\":\"" + methodId + "\"}");
    }

    private ResultActions reverse(String receipt, String key, String reason) throws Exception {
        return api.send(owner, post("/api/finance/receipts/" + receipt + "/reversal").header("Idempotency-Key", key), "{\"reason\":\"" + reason + "\"}");
    }

    private ResultActions adjust(String receivable, String key, String type, String amount, String reason) throws Exception {
        return api.send(owner, post("/api/finance/receivables/" + receivable + "/adjustments").header("Idempotency-Key", key),
                "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"reason\":\"" + reason + "\"}");
    }

    private ResultActions payment(String payable, String key, String amount, String methodId) throws Exception {
        return api.send(owner, post("/api/finance/payables/" + payable + "/payments").header("Idempotency-Key", key),
                "{\"amount\":\"" + amount + "\",\"paymentMethodId\":\"" + methodId + "\"}");
    }

    private String method(String code) {
        return jdbc.queryForObject("select id::text from finance.payment_method where code = ?", String.class, code);
    }

    private String openWorkOrder() throws Exception {
        sequence++;
        String customer = id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"Cliente Financeiro\",\"phone\":\"34999990000\"}")
                .andExpect(status().isCreated()));
        String vehicle = id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"FIN" + (1000 + sequence)
                + "\",\"manufacturer\":\"Volvo\",\"model\":\"FH\"}").andExpect(status().isCreated()));
        return id(send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                + "\",\"complaint\":\"Vazamento no cilindro\"}").andExpect(status().isCreated()));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        return api.send(owner, request, body);
    }

    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }

    private static LocalDate today() { return LocalDate.now(WORKSHOP); }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @FunctionalInterface
    private interface Indexed<T> { T call(int index) throws Exception; }

    /** Requisições HTTP reais e simultâneas, liberadas por uma barreira comum. */
    private <T> List<T> inParallel(int threads, Indexed<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit((Callable<T>) () -> {
                    barrier.await();
                    return action.call(index);
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
