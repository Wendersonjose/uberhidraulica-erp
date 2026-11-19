package br.com.uberhidraulica.erp.finance;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0024 etapa B (DR-0024) contra PostgreSQL real: parcelas, recebível avulso, juros e multa, taxas de cartão,
 * parcelas e aprovação de contas a pagar, fornecedor e conciliação manual.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class Task0024FinanceStageBIntegrationTest {
    private static final String OWNER_EMAIL = "owner-stage-b@example.test";
    private static final String BOOTSTRAP_PASSWORD = "stage-b-bootstrap-password";
    private static final String PASSWORD = "stage-b-operational-password";
    private static final ZoneId WORKSHOP = ZoneId.of("America/Sao_Paulo");

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Stage B");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    ApiSessions api;
    Session owner;
    int sequence;

    @BeforeEach
    void clean() throws Exception {
        for (String table : StageBTables.BEFORE_COMMERCIAL) jdbc.update("delete from " + table);
        CommercialFixtures.clean(jdbc);
        for (String table : new String[]{"finance.cash_movement", "finance.cash_session", "workorder.work_order_product",
                "workorder.work_order_service", "workorder.work_order_status_history", "workorder.work_order",
                "crm.vehicle_ownership", "crm.vehicle", "crm.customer", "finance.expense_category", "finance.revenue_category"})
            jdbc.update("delete from " + table);
        for (String table : StageBTables.AFTER_COMMERCIAL) jdbc.update("delete from " + table);
        jdbc.update("update finance.settings set value = '0' where key = 'DEFAULT_RECEIVABLE_DUE_DAYS'");
        jdbc.update("update inventory.settings set value = 'DISABLED' where key = 'WORK_ORDER_WRITE_OFF'");
        jdbc.update("update workorder.status_automation set enabled = true");
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
    }

    // ================================================================ B1 — parcelas do recebível

    @Test
    void installmentPlanSplitsTheReceivableAndReceiptsSettleTheOldestFirst() throws Exception {
        String receivable = billed(openWorkOrder(), "Revisão completa", "300.00");
        String plan = installments(today(), "100.00", today().plusDays(10), "100.00", today().plusDays(40), "100.00");

        replan(receivable, "plan-1", plan).andExpect(status().isCreated())
                .andExpect(jsonPath("$.installmentPlan.version").value(1))
                .andExpect(jsonPath("$.installmentPlan.installments.length()").value(3))
                .andExpect(jsonPath("$.installmentPlan.installments[2].dueDate").value(today().plusDays(40).toString()))
                .andExpect(jsonPath("$.dueDate").value(today().toString()));
        // O mesmo pedido devolve o plano gravado; a mesma chave com outro plano é recusada.
        replan(receivable, "plan-1", plan).andExpect(status().isOk());
        replan(receivable, "plan-1", installments(today(), "300.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        replan(receivable, "plan-bad", installments(today(), "100.00", today(), "100.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSTALLMENT_TOTAL_MISMATCH"));

        receipt(receivable, "inst-rec-1", "150.00", method("PIX")).andExpect(status().isCreated());
        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(status().isOk())
                .andExpect(jsonPath("$.installmentPlan.installments[0].openAmount").value(0.0))
                .andExpect(jsonPath("$.installmentPlan.installments[0].status").value("QUITADO"))
                .andExpect(jsonPath("$.installmentPlan.installments[1].openAmount").value(50.00))
                .andExpect(jsonPath("$.installmentPlan.installments[1].status").value("PARCIAL"))
                .andExpect(jsonPath("$.installmentPlan.editable").value(false))
                .andExpect(jsonPath("$.dueDate").value(today().plusDays(10).toString()))
                .andExpect(jsonPath("$.status").value("PARCIAL"));

        // Com recebimento ativo, o plano não muda.
        replan(receivable, "plan-2", installments(today(), "300.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIVABLE_HAS_RECEIPTS"));

        // Vencimento por parcela, com histórico; parcela quitada não tem vencimento a alterar.
        api.send(owner, put("/api/finance/receivables/" + receivable + "/due-date").header("Idempotency-Key", "inst-due-2"),
                "{\"installmentNumber\":2,\"dueDate\":\"" + today().plusDays(15) + "\",\"reason\":\"Cliente pediu mais prazo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installmentPlan.installments[1].dueDate").value(today().plusDays(15).toString()))
                .andExpect(jsonPath("$.dueDateChanges[0].installmentNumber").value(2))
                .andExpect(jsonPath("$.dueDateChanges[0].previousDueDate").value(today().plusDays(10).toString()));
        api.send(owner, put("/api/finance/receivables/" + receivable + "/due-date").header("Idempotency-Key", "inst-due-1"),
                "{\"installmentNumber\":1,\"dueDate\":\"" + today().plusDays(15) + "\",\"reason\":\"Tentativa\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSTALLMENT_NOT_OPEN"));

        // Lista, painel e inadimplência leem por parcela: a parcela 2 vencida há 40 dias cai em 31–60.
        jdbc.update("update finance.receivable_installment set due_date = ? where receivable_id = ?::uuid and number = 2",
                Date.valueOf(today().minusDays(40)), receivable);
        api.read(owner, "/api/finance/receivables?status=VENCIDO").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].installmentCount").value(3));
        api.read(owner, "/api/finance/reports/aging").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutstanding").value(150.00))
                .andExpect(jsonPath("$.buckets[0].amount").value(100.00))
                .andExpect(jsonPath("$.buckets[2].amount").value(50.00));
        api.read(owner, "/api/finance/reports/overview?from=" + today() + "&to=" + today())
                .andExpect(jsonPath("$.receivables.overdueAmount").value(50.00))
                .andExpect(jsonPath("$.receivables.openAmount").value(150.00))
                .andExpect(jsonPath("$.receivables.openCount").value(1));
        api.read(owner, "/api/finance/cash-flow?from=" + today() + "&to=" + today().plusDays(60))
                .andExpect(jsonPath("$.forecast.inflows").value(100.00));
    }

    @Test
    void databaseRefusesAnInstallmentPlanThatDoesNotAddUpAndAnyChangeToAnInstallmentAmount() throws Exception {
        String receivable = billed(openWorkOrder(), "Troca de vedação", "200.00");
        UUID author = UUID.randomUUID();
        // Plano e parcela na mesma instrução: a conferência roda no fim da transação e recusa a soma errada.
        assertThatThrownBy(() -> jdbc.update("with plan as (insert into finance.receivable_installment_plan (receivable_id, version, reason,"
                + " created_at, created_by, idempotency_key) values (?::uuid, 1, 'Teste', now(), ?, 'db-plan-1') returning receivable_id)"
                + " insert into finance.receivable_installment (id, receivable_id, plan_version, number, amount, due_date)"
                + " select ?, receivable_id, 1, 1, 150.00, current_date from plan", receivable, author, UUID.randomUUID()))
                .hasMessageContaining("installment_plan_total_mismatch");
        assertThatThrownBy(() -> jdbc.update("insert into finance.receivable_installment_plan (receivable_id, version, reason, created_at,"
                + " created_by, idempotency_key) values (?::uuid, 1, 'Teste', now(), ?, 'db-plan-2')", receivable, author))
                .hasMessageContaining("installment_plan_empty");

        replan(receivable, "db-plan-ok", installments(today(), "120.00", today().plusDays(30), "80.00")).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("update finance.receivable_installment set amount = 1 where receivable_id = ?::uuid", receivable))
                .hasMessageContaining("installment_immutable");
    }

    // ================================================================ B2 — recebível avulso

    @Test
    void manualReceivableIsCreatedSplitReportedAsOtherRevenueAndCancelledWithoutReceipts() throws Exception {
        String customer = customer("Cliente Balcão");
        String category = id(send(post("/api/finance/revenue-categories"), "{\"name\":\"Venda de balcão\"}").andExpect(status().isCreated()));
        String body = "{\"customerId\":\"" + customer + "\",\"description\":\"Venda de mangueira\",\"revenueCategoryId\":\"" + category
                + "\",\"amount\":\"100.01\",\"dueDate\":\"" + today() + "\",\"installments\":2}";

        String manual = id(api.send(owner, post("/api/finance/receivables").header("Idempotency-Key", "manual-1"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("MANUAL"))
                .andExpect(jsonPath("$.workOrderNumber").doesNotExist())
                .andExpect(jsonPath("$.revenueCategoryName").value("Venda de balcão"))
                .andExpect(jsonPath("$.installmentPlan.installments[0].amount").value(50.00))
                .andExpect(jsonPath("$.installmentPlan.installments[1].amount").value(50.01))
                .andExpect(jsonPath("$.installmentPlan.installments[1].dueDate").value(today().plusMonths(1).toString())));
        api.send(owner, post("/api/finance/receivables").header("Idempotency-Key", "manual-1"), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(manual));
        assertThat(jdbc.queryForObject("select count(*) from finance.receivable where origin = 'MANUAL'", Integer.class)).isEqualTo(1);

        // Recebível da OS no mesmo período: o demonstrativo separa receita de serviços e outras receitas.
        billed(openWorkOrder(), "Serviço de OS", "400.00");
        String period = "?from=" + today() + "&to=" + today();
        api.read(owner, "/api/finance/reports/income-statement" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.grossRevenue").value(400.00)).andExpect(jsonPath("$.otherRevenue").value(100.01))
                .andExpect(jsonPath("$.netRevenue").value(500.01));
        api.read(owner, "/api/finance/reports/work-orders" + period).andExpect(jsonPath("$.workOrders.length()").value(1));
        api.read(owner, "/api/finance/receivables?origin=MANUAL").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Venda de mangueira"));
        api.read(owner, "/api/finance/dashboard" + period).andExpect(jsonPath("$.revenue").value(500.01));

        // Cancelamento: com recebimento ativo, recusado; sem ele, cancela e preserva o histórico.
        String receipt = id(receipt(manual, "manual-rec", "10.00", method("PIX")).andExpect(status().isCreated()));
        send(post("/api/finance/receivables/" + manual + "/cancel"), "{\"reason\":\"Venda desfeita\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIVABLE_HAS_RECEIPTS"));
        api.send(owner, post("/api/finance/receipts/" + receipt + "/reversal").header("Idempotency-Key", "manual-rev"),
                "{\"reason\":\"Devolvido\"}").andExpect(status().isCreated());
        send(post("/api/finance/receivables/" + manual + "/cancel"), "{\"reason\":\"Venda desfeita\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"));
    }

    @Test
    void manualReceivableRequiresItsPermissionActiveCustomerAndCategory() throws Exception {
        String customer = customer("Cliente Permissão");
        String category = id(send(post("/api/finance/revenue-categories"), "{\"name\":\"Reembolso\"}").andExpect(status().isCreated()));
        String body = "{\"customerId\":\"" + customer + "\",\"description\":\"Reembolso de frete\",\"revenueCategoryId\":\"" + category
                + "\",\"amount\":\"50.00\",\"dueDate\":\"" + today() + "\"}";
        Session admin = api.user(owner, "admin-stage-b@example.test", "GERENTE_ADMINISTRATIVO");
        api.send(admin, post("/api/finance/receivables").header("Idempotency-Key", "perm-1"), body).andExpect(status().isForbidden());
        Session financial = api.user(owner, "finance-stage-b@example.test", "GERENTE_FINANCEIRO");
        api.send(financial, post("/api/finance/receivables").header("Idempotency-Key", "perm-2"), body).andExpect(status().isCreated());

        send(put("/api/finance/revenue-categories/" + category), "{\"name\":\"Reembolso\",\"active\":false}").andExpect(status().isOk());
        api.send(owner, post("/api/finance/receivables").header("Idempotency-Key", "perm-3"), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVENUE_CATEGORY_INACTIVE"));
        api.send(owner, post("/api/finance/receivables").header("Idempotency-Key", "perm-4"),
                body.replace(customer, UUID.randomUUID().toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));

        String fromWorkOrder = billed(openWorkOrder(), "Serviço", "10.00");
        send(post("/api/finance/receivables/" + fromWorkOrder + "/cancel"), "{\"reason\":\"Tentativa\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIVABLE_FROM_WORK_ORDER"));
    }

    // ================================================================ B8 — painel sobre a receita líquida

    @Test
    void dashboardComputesProfitOnNetRevenueLikeTheIncomeStatement() throws Exception {
        String receivable = billed(openWorkOrder(), "Serviço", "500.00");
        adjust(receivable, "q8-disc", "DISCOUNT", "50.00", "Desconto comercial").andExpect(status().isCreated());
        String period = "?from=" + today() + "&to=" + today();
        api.read(owner, "/api/finance/dashboard" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.revenue").value(500.00)).andExpect(jsonPath("$.discounts").value(50.00))
                .andExpect(jsonPath("$.netRevenue").value(450.00)).andExpect(jsonPath("$.grossProfit").value(450.00))
                .andExpect(jsonPath("$.operatingResult").value(450.00));
        api.read(owner, "/api/finance/reports/income-statement" + period).andExpect(jsonPath("$.operatingResult").value(450.00));
    }

    // ================================================================ B3 — juros e multa sugeridos

    @Test
    void lateChargeIsSuggestedAppliedOnceWaivedWithReasonAndComesBackWhenTheSurchargeIsReversed() throws Exception {
        String receivable = billed(openWorkOrder(), "Reforma de cilindro", "1000.00");
        jdbc.update("update finance.receivable set due_date = ? where id = ?::uuid", Date.valueOf(today().minusDays(10)), receivable);

        // Multa 2% de 1000 = 20,00; juros 1% a.m. por 10 dias = 1000 x 1% / 30 x 10 = 3,33.
        api.read(owner, "/api/finance/receivables/" + receivable + "/late-charge").andExpect(status().isOk())
                .andExpect(jsonPath("$.feeAmount").value(20.00)).andExpect(jsonPath("$.interestAmount").value(3.33))
                .andExpect(jsonPath("$.total").value(23.33)).andExpect(jsonPath("$.lines[0].days").value(10));
        api.read(owner, "/api/finance/receivables/" + receivable + "/late-charge?on=" + today().minusDays(5))
                .andExpect(jsonPath("$.total").value(21.67));

        lateCharge(receivable, "late-stale", "APPLIED", "23.30", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LATE_CHARGE_CHANGED"));
        lateCharge(receivable, "late-apply", "APPLIED", "23.33", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.surchargeAmount").value(23.33)).andExpect(jsonPath("$.originalAmount").value(1000.00))
                .andExpect(jsonPath("$.outstandingBalance").value(1023.33))
                .andExpect(jsonPath("$.adjustments[0].origin").value("LATE_CHARGE"));
        lateCharge(receivable, "late-apply", "APPLIED", "23.33", null).andExpect(status().isOk());
        // Mesmo dia: multa já cobrada e juros contados até hoje.
        api.read(owner, "/api/finance/receivables/" + receivable + "/late-charge").andExpect(jsonPath("$.total").value(0));
        lateCharge(receivable, "late-again", "APPLIED", "0.00", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LATE_CHARGE_NOT_DUE"));

        // Estornar o acréscimo devolve a sugestão; dispensar exige motivo e não cria acréscimo.
        String adjustment = JsonPath.read(api.read(owner, "/api/finance/receivables/" + receivable).andReturn().getResponse()
                .getContentAsString(), "$.adjustments[0].id");
        api.send(owner, post("/api/finance/adjustments/" + adjustment + "/reversal").header("Idempotency-Key", "late-rev"),
                "{\"reason\":\"Cliente provou pagamento em dia\"}").andExpect(status().isCreated());
        api.read(owner, "/api/finance/receivables/" + receivable + "/late-charge").andExpect(jsonPath("$.total").value(23.33));
        lateCharge(receivable, "late-waive-no-reason", "WAIVED", "23.33", null).andExpect(status().isBadRequest());
        lateCharge(receivable, "late-waive", "WAIVED", "23.33", "Cliente antigo, atraso combinado").andExpect(status().isCreated())
                .andExpect(jsonPath("$.surchargeAmount").value(0.0));
        api.read(owner, "/api/finance/receivables/" + receivable + "/late-charge").andExpect(jsonPath("$.total").value(0));
        api.read(owner, "/api/finance/receivables/" + receivable + "/late-charges").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].counts").value(false)).andExpect(jsonPath("$[1].decision").value("WAIVED"));

        // Sem FINANCE_ADJUST, só consulta.
        Session admin = api.user(owner, "admin-late@example.test", "GERENTE_ADMINISTRATIVO");
        api.read(admin, "/api/finance/receivables/" + receivable + "/late-charge").andExpect(status().isOk());
        api.send(admin, post("/api/finance/receivables/" + receivable + "/late-charge").header("Idempotency-Key", "late-admin"),
                "{\"decision\":\"APPLIED\",\"expectedTotal\":\"0.00\"}").andExpect(status().isForbidden());
    }

    @Test
    void lateChargeSettingsAreValidatedAndAppliedPerInstallment() throws Exception {
        send(put("/api/finance/settings"), "{\"lateFeePercent\":\"25\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FINANCE_SETTING"));
        send(put("/api/finance/settings"), "{\"lateFeePercent\":\"10\",\"lateInterestMonthlyPercent\":\"3\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lateFeePercent").value(10.00)).andExpect(jsonPath("$.defaultReceivableDueDays").value(0));
        String receivable = billed(openWorkOrder(), "Serviço parcelado", "200.00");
        replan(receivable, "late-plan", installments(today(), "100.00", today().plusDays(30), "100.00")).andExpect(status().isCreated());
        jdbc.update("update finance.receivable_installment set due_date = ? where receivable_id = ?::uuid and number = 1",
                Date.valueOf(today().minusDays(30)), receivable);
        // Só a parcela 1 está vencida: multa 10% de 100 = 10,00; juros 3% a.m. por 30 dias = 3,00.
        api.read(owner, "/api/finance/receivables/" + receivable + "/late-charge")
                .andExpect(jsonPath("$.lines.length()").value(1)).andExpect(jsonPath("$.lines[0].installmentNumber").value(1))
                .andExpect(jsonPath("$.total").value(13.00));
        send(put("/api/finance/settings"), "{\"lateFeePercent\":\"2\",\"lateInterestMonthlyPercent\":\"1\"}").andExpect(status().isOk());
    }

    // ================================================================ B4 — taxas e parcelas de cartão

    @Test
    void cardFeeIsFrozenOnTheReceiptAndCountsAsFinancialExpense() throws Exception {
        String credit = method("CARTAO_CREDITO");
        send(put("/api/finance/payment-methods/" + method("DINHEIRO") + "/fees"),
                "{\"fees\":[{\"installments\":1,\"feePercent\":\"1.00\",\"settlementDays\":0}]}").andExpect(status().isBadRequest());
        send(put("/api/finance/payment-methods/" + credit + "/fees"), "{\"fees\":[{\"installments\":1,\"feePercent\":\"3.50\","
                + "\"settlementDays\":30},{\"installments\":3,\"feePercent\":\"5.99\",\"settlementDays\":30}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        String receivable = billed(openWorkOrder(), "Serviço no cartão", "300.00");
        cardReceipt(receivable, "card-2x", "300.00", credit, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTALLMENTS_NOT_ALLOWED"));
        cardReceipt(receivable, "pix-2x", "300.00", method("PIX"), 2).andExpect(status().isConflict());
        cardReceipt(receivable, "card-3x", "300.00", credit, 3).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardFee.installments").value(3)).andExpect(jsonPath("$.cardFee.feeAmount").value(17.97))
                .andExpect(jsonPath("$.netAmount").value(282.03))
                .andExpect(jsonPath("$.cardFee.expectedSettlementOn").value(today().plusDays(30).toString()));

        // Trocar a tabela depois não reescreve o recebimento gravado.
        send(put("/api/finance/payment-methods/" + credit + "/fees"), "{\"fees\":[]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        api.read(owner, "/api/finance/receivables/" + receivable).andExpect(jsonPath("$.receipts[0].cardFee.feeAmount").value(17.97));

        String period = "?from=" + today() + "&to=" + today();
        api.read(owner, "/api/finance/reports/income-statement" + period).andExpect(status().isOk())
                .andExpect(jsonPath("$.financialExpenses").value(17.97)).andExpect(jsonPath("$.operatingResult").value(282.03));
        api.read(owner, "/api/finance/dashboard" + period).andExpect(jsonPath("$.cardFees").value(17.97))
                .andExpect(jsonPath("$.operatingResult").value(282.03));
    }

    // ================================================================ B5 — parcelas e aprovação de contas a pagar

    @Test
    void payablesAboveTheThresholdNeedApprovalFromAnotherUserUnlessTheOwnerApprovesHisOwn() throws Exception {
        send(put("/api/finance/settings"), "{\"payableApprovalThreshold\":\"1000.00\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.payableApprovalThreshold").value(1000.00));
        String rent = category("Aluguel");
        Session financial = api.user(owner, "finance-approval@example.test", "GERENTE_FINANCEIRO");
        Session admin = api.user(owner, "admin-approval@example.test", "GERENTE_ADMINISTRATIVO");

        String small = payable(owner, "appr-small", "Material de limpeza", rent, "1000.00");
        api.read(owner, "/api/finance/payables/" + small).andExpect(jsonPath("$.approvalStatus").value("NOT_REQUIRED"));

        String big = payable(financial, "appr-big", "Aluguel do galpão", rent, "2500.00");
        api.read(owner, "/api/finance/payables/" + big).andExpect(jsonPath("$.approvalStatus").value("PENDING"));
        api.read(owner, "/api/finance/payables?approval=PENDING").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].approvalStatus").value("PENDING"));
        pay(financial, big, "appr-pay-early").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYABLE_APPROVAL_REQUIRED"));
        // O banco também recusa, mesmo sem passar pela aplicação.
        assertThatThrownBy(() -> jdbc.update("insert into finance.payable_payment (id, payable_id, amount, payment_method_id, payment_method_name,"
                + " paid_on, recorded_at, recorded_by, idempotency_key) values (?, ?::uuid, 10, ?::uuid, 'PIX', current_date, now(), ?, 'db-pay')",
                UUID.randomUUID(), big, method("PIX"), UUID.randomUUID())).hasMessageContaining("payable_approval_required");

        api.send(financial, post("/api/finance/payables/" + big + "/approval"), "{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAYABLE_APPROVAL_SEGREGATION"));
        api.send(admin, post("/api/finance/payables/" + big + "/approval"), "{}").andExpect(status().isForbidden());
        send(post("/api/finance/payables/" + big + "/approval"), "{\"notes\":\"Contrato conferido\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED")).andExpect(jsonPath("$.approval.notes").value("Contrato conferido"));
        send(post("/api/finance/payables/" + big + "/approval"), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYABLE_ALREADY_APPROVED"));
        pay(financial, big, "appr-pay").andExpect(status().isCreated());

        // O Dono aprova a conta que ele mesmo lançou (FINANCE_PAYABLE_APPROVE_OWN).
        String ownerBig = payable(owner, "appr-owner", "Compressor", rent, "1500.00");
        send(post("/api/finance/payables/" + ownerBig + "/approval"), "{}").andExpect(status().isOk());
        send(put("/api/finance/settings"), "{\"payableApprovalThreshold\":\"0\"}").andExpect(status().isOk());
    }

    @Test
    void installmentPayableCreatesAGroupWithConsolidatedTotalsAndGroupApproval() throws Exception {
        send(put("/api/finance/settings"), "{\"payableApprovalThreshold\":\"900.00\"}").andExpect(status().isOk());
        String category = category("Equipamentos");
        String body = "{\"description\":\"Elevador hidráulico\",\"supplier\":\"Máquinas Sul\",\"categoryId\":\"" + category
                + "\",\"totalAmount\":\"1000.00\",\"installments\":3,\"firstDueDate\":\"" + today() + "\"}";
        String group = id(send(post("/api/finance/payables/installments").header("Idempotency-Key", "grp-1"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.installmentCount").value(3)).andExpect(jsonPath("$.payables.length()").value(3))
                .andExpect(jsonPath("$.payables[0].amount").value(333.33)).andExpect(jsonPath("$.payables[2].amount").value(333.34))
                .andExpect(jsonPath("$.payables[2].description").value("Elevador hidráulico (3/3)"))
                .andExpect(jsonPath("$.payables[1].dueDate").value(today().plusMonths(1).toString()))
                // O limite vale para o total do grupo (1000 > 900), não para a parcela de 333,33.
                .andExpect(jsonPath("$.payables[0].approvalStatus").value("PENDING")));
        send(post("/api/finance/payables/installments").header("Idempotency-Key", "grp-1"), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(group));

        Session financial = api.user(owner, "finance-group@example.test", "GERENTE_FINANCEIRO");
        api.send(financial, post("/api/finance/payable-groups/" + group + "/approval"), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.payables[*].approvalStatus").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("APPROVED"))));
        String first = JsonPath.read(api.read(owner, "/api/finance/payable-groups/" + group).andReturn().getResponse().getContentAsString(),
                "$.payables[0].id");
        pay(owner, first, "grp-pay-1", "333.33").andExpect(status().isCreated());
        api.read(owner, "/api/finance/payable-groups/" + group).andExpect(jsonPath("$.paidAmount").value(333.33))
                .andExpect(jsonPath("$.outstandingBalance").value(666.67));
        api.read(owner, "/api/finance/payables?groupId=" + group).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].installmentNumber").value(1));
        send(put("/api/finance/settings"), "{\"payableApprovalThreshold\":\"0\"}").andExpect(status().isOk());
    }

    // ================================================================ B6 — fornecedor vinculado

    @Test
    void payableLinksToAPurchasingSupplierKeepingTheNameAsSnapshot() throws Exception {
        String supplier = JsonPath.read(send(post("/api/purchasing/suppliers"), "{\"personType\":\"PJ\",\"legalName\":\"Hidro Peças Ltda\","
                + "\"tradeName\":\"Hidro Peças\",\"document\":\"" + cnpj() + "\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        String category = category("Peças");
        String payable = id(api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "sup-1"), "{\"description\":\"Kit de vedação\","
                + "\"supplierId\":\"" + supplier + "\",\"categoryId\":\"" + category + "\",\"amount\":\"310.00\",\"dueDate\":\"" + today() + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.supplierId").value(supplier))
                .andExpect(jsonPath("$.supplier").value("Hidro Peças")));
        api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "sup-2"), "{\"description\":\"Outro\",\"supplierId\":\""
                + UUID.randomUUID() + "\",\"categoryId\":\"" + category + "\",\"amount\":\"10.00\",\"dueDate\":\"" + today() + "\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        send(post("/api/purchasing/suppliers/" + supplier + "/inactivate"), "{\"reason\":\"Encerrou atividades\"}").andExpect(status().isOk());
        api.send(owner, post("/api/finance/payables").header("Idempotency-Key", "sup-3"), "{\"description\":\"Mais um\",\"supplierId\":\""
                + supplier + "\",\"categoryId\":\"" + category + "\",\"amount\":\"10.00\",\"dueDate\":\"" + today() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
        // O snapshot continua: inativar o fornecedor não reescreve a conta já lançada.
        api.read(owner, "/api/finance/payables/" + payable).andExpect(jsonPath("$.supplier").value("Hidro Peças"));
    }

    // ================================================================ B7 — contas financeiras e conciliação manual

    @Test
    void manualReconciliationMatchesEntriesToStatementLinesOnceAndBlocksReversalWhileReconciled() throws Exception {
        String bank = id(send(post("/api/finance/accounts"), "{\"name\":\"Banco PJ\",\"type\":\"BANK\",\"bankName\":\"Banco\","
                + "\"branch\":\"0001\",\"accountNumber\":\"12345-6\"}").andExpect(status().isCreated()));
        send(post("/api/finance/accounts"), "{\"name\":\"banco pj\",\"type\":\"BANK\"}").andExpect(status().isConflict());

        String receivable = billed(openWorkOrder(), "Serviço conciliado", "500.00");
        String pix = id(receipt(receivable, "rec-pix", "200.00", method("PIX")).andExpect(status().isCreated()));
        String other = id(receipt(receivable, "rec-pix-2", "100.00", method("PIX")).andExpect(status().isCreated()));
        String period = "?from=" + today() + "&to=" + today();
        api.read(owner, "/api/finance/reconciliation/entries" + period + "&reconciled=false").andExpect(jsonPath("$.length()").value(2));

        reconcile("conc-1", bank, "RECEIPT", pix, "200.00", "EXT-001", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.difference").value(0.0)).andExpect(jsonPath("$.active").value(true));
        reconcile("conc-1", bank, "RECEIPT", pix, "200.00", "EXT-001", null).andExpect(status().isOk());
        reconcile("conc-2", bank, "RECEIPT", pix, "200.00", "EXT-009", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENTRY_ALREADY_RECONCILED"));
        reconcile("conc-3", bank, "RECEIPT", other, "100.00", "ext-001", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATEMENT_REFERENCE_ALREADY_USED"));
        reconcile("conc-4", bank, "RECEIPT", other, "99.50", "EXT-002", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_DIFFERENCE_REASON_REQUIRED"));
        String withDifference = id(reconcile("conc-5", bank, "RECEIPT", other, "99.50", "EXT-002", "Tarifa bancária do PIX")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.difference").value(-0.50)));
        api.read(owner, "/api/finance/reconciliation/entries" + period + "&accountId=" + bank).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountName").value("Banco PJ"));

        // Conciliado não é estornado; desfeita a conciliação, o estorno passa.
        api.send(owner, post("/api/finance/receipts/" + other + "/reversal").header("Idempotency-Key", "conc-rev-early"),
                "{\"reason\":\"Pix devolvido\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENTRY_RECONCILED"));
        api.send(owner, post("/api/finance/reconciliations/" + withDifference + "/reversal").header("Idempotency-Key", "conc-undo"),
                "{\"reason\":\"Conciliado na linha errada\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(false));
        api.send(owner, post("/api/finance/reconciliations/" + withDifference + "/reversal").header("Idempotency-Key", "conc-undo-2"),
                "{\"reason\":\"De novo\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECONCILIATION_ALREADY_REVERSED"));
        api.send(owner, post("/api/finance/receipts/" + other + "/reversal").header("Idempotency-Key", "conc-rev"),
                "{\"reason\":\"Pix devolvido\"}").andExpect(status().isCreated());
        // A referência liberada pelo estorno pode ser usada de novo.
        String category = category("Tarifas");
        String payable = payable(owner, "conc-payable", "Tarifa", category, "20.00");
        String payment = id(pay(owner, payable, "conc-pay", "20.00").andExpect(status().isCreated()));
        reconcile("conc-6", bank, "PAYMENT", payment, "20.00", "EXT-002", null).andExpect(status().isCreated());

        Session admin = api.user(owner, "admin-reconcile@example.test", "GERENTE_ADMINISTRATIVO");
        api.send(admin, post("/api/finance/reconciliations").header("Idempotency-Key", "conc-admin"), "{\"accountId\":\"" + bank
                + "\",\"entryType\":\"RECEIPT\",\"entryId\":\"" + pix + "\",\"statementDate\":\"" + today()
                + "\",\"statementAmount\":\"1.00\",\"statementReference\":\"X\"}").andExpect(status().isForbidden());
    }

    @Test
    void cardReceiptIsReconciledAgainstItsNetAmount() throws Exception {
        String credit = method("CARTAO_CREDITO");
        send(put("/api/finance/payment-methods/" + credit + "/fees"), "{\"fees\":[{\"installments\":1,\"feePercent\":\"4.00\","
                + "\"settlementDays\":30}]}").andExpect(status().isOk());
        String card = id(send(post("/api/finance/accounts"), "{\"name\":\"Adquirente\",\"type\":\"CARD\"}").andExpect(status().isCreated()));
        String receivable = billed(openWorkOrder(), "Serviço cartão", "250.00");
        String receipt = id(cardReceipt(receivable, "net-rec", "250.00", credit, 1).andExpect(status().isCreated()));
        reconcile("net-conc", card, "RECEIPT", receipt, "240.00", "REP-1", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.expectedAmount").value(240.00)).andExpect(jsonPath("$.difference").value(0.0));
        send(put("/api/finance/payment-methods/" + credit + "/fees"), "{\"fees\":[]}").andExpect(status().isOk());
    }

    // ================================================================ apoio

    ResultActions lateCharge(String receivable, String key, String decision, String expectedTotal, String reason) throws Exception {
        return api.send(owner, post("/api/finance/receivables/" + receivable + "/late-charge").header("Idempotency-Key", key),
                "{\"decision\":\"" + decision + "\",\"expectedTotal\":\"" + expectedTotal + "\""
                        + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    ResultActions cardReceipt(String receivable, String key, String amount, String methodId, int installments) throws Exception {
        return api.send(owner, post("/api/finance/receivables/" + receivable + "/receipts").header("Idempotency-Key", key),
                "{\"amount\":\"" + amount + "\",\"paymentMethodId\":\"" + methodId + "\",\"installments\":" + installments + "}");
    }

    String payable(Session as, String key, String description, String category, String amount) throws Exception {
        return id(api.send(as, post("/api/finance/payables").header("Idempotency-Key", key), "{\"description\":\"" + description
                + "\",\"categoryId\":\"" + category + "\",\"amount\":\"" + amount + "\",\"dueDate\":\"" + today() + "\"}")
                .andExpect(status().isCreated()));
    }

    ResultActions pay(Session as, String payable, String key) throws Exception {
        Number outstanding = JsonPath.read(api.read(owner, "/api/finance/payables/" + payable).andReturn().getResponse().getContentAsString(),
                "$.outstandingBalance");
        return pay(as, payable, key, new java.math.BigDecimal(outstanding.toString()).setScale(2).toPlainString());
    }

    ResultActions pay(Session as, String payable, String key, String amount) throws Exception {
        return api.send(as, post("/api/finance/payables/" + payable + "/payments").header("Idempotency-Key", key),
                "{\"amount\":\"" + amount + "\",\"paymentMethodId\":\"" + method("PIX") + "\"}");
    }

    ResultActions reconcile(String key, String account, String type, String entry, String amount, String reference, String reason)
            throws Exception {
        return api.send(owner, post("/api/finance/reconciliations").header("Idempotency-Key", key), "{\"accountId\":\"" + account
                + "\",\"entryType\":\"" + type + "\",\"entryId\":\"" + entry + "\",\"statementDate\":\"" + today()
                + "\",\"statementAmount\":\"" + amount + "\",\"statementReference\":\"" + reference + "\""
                + (reason == null ? "" : ",\"differenceReason\":\"" + reason + "\"") + "}");
    }

    static final java.util.concurrent.atomic.AtomicInteger CNPJ_SEQUENCE = new java.util.concurrent.atomic.AtomicInteger(4100);

    static String cnpj() {
        String base = String.format("%08d0001", CNPJ_SEQUENCE.incrementAndGet());
        int d1 = digit(base, new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = digit(base + d1, new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    static int digit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }

    String installments(Object... dueAndAmount) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < dueAndAmount.length; i += 2) {
            if (i > 0) json.append(',');
            json.append("{\"dueDate\":\"").append(dueAndAmount[i]).append("\",\"amount\":\"").append(dueAndAmount[i + 1]).append("\"}");
        }
        return json.append(']').toString();
    }

    ResultActions replan(String receivable, String key, String installments) throws Exception {
        return api.send(owner, put("/api/finance/receivables/" + receivable + "/installments").header("Idempotency-Key", key),
                "{\"reason\":\"Negociado com o cliente\",\"installments\":" + installments + "}");
    }

    String billed(String order, String description, String price) throws Exception {
        String quote = id(send(post("/api/work-orders/" + order + "/quotes"), "").andExpect(status().isCreated()));
        String draft = send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions"),
                "{\"items\":[{\"description\":\"" + description + "\",\"quantity\":\"1\",\"unitPrice\":\"" + price + "\"}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<String> revisions = JsonPath.read(draft, "$.revisions[*].id");
        String revision = revisions.get(revisions.size() - 1);
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/present"), "").andExpect(status().isOk());
        String content = api.read(owner, "/api/work-orders/" + order + "/quotes/" + quote).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(content, "$.items[*].revisions[?(@.description == '" + description + "')].id");
        send(post("/api/work-orders/" + order + "/quotes/" + quote + "/revisions/" + revision + "/decisions"),
                "{\"contactChannel\":\"PRESENCIAL\",\"authorizedBy\":\"Cliente\",\"decisions\":[{\"itemReference\":\"" + ids.get(0)
                        + "\",\"decision\":\"APPROVE\"}]}").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/start-execution"), "").andExpect(status().isOk());
        send(post("/api/work-orders/" + order + "/finish"), "").andExpect(status().isOk());
        return JsonPath.read(api.read(owner, "/api/finance/work-orders/" + order + "/receivable").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    String category(String name) throws Exception {
        return id(send(post("/api/finance/expense-categories"), "{\"name\":\"" + name + "\"}").andExpect(status().isCreated()));
    }

    ResultActions receipt(String receivable, String key, String amount, String methodId) throws Exception {
        return api.send(owner, post("/api/finance/receivables/" + receivable + "/receipts").header("Idempotency-Key", key),
                "{\"amount\":\"" + amount + "\",\"paymentMethodId\":\"" + methodId + "\"}");
    }

    ResultActions adjust(String receivable, String key, String type, String amount, String reason) throws Exception {
        return api.send(owner, post("/api/finance/receivables/" + receivable + "/adjustments").header("Idempotency-Key", key),
                "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"reason\":\"" + reason + "\"}");
    }

    String method(String code) {
        return jdbc.queryForObject("select id::text from finance.payment_method where code = ?", String.class, code);
    }

    String customer(String name) throws Exception {
        return id(send(post("/api/customers"), "{\"personType\":\"PF\",\"name\":\"" + name + "\",\"phone\":\"34999990000\"}")
                .andExpect(status().isCreated()));
    }

    String openWorkOrder() throws Exception {
        sequence++;
        String customer = customer("Cliente Etapa B");
        String vehicle = id(send(post("/api/vehicles"), "{\"customerId\":\"" + customer + "\",\"plate\":\"ETB" + (1000 + sequence)
                + "\",\"manufacturer\":\"Volvo\",\"model\":\"FH\"}").andExpect(status().isCreated()));
        return id(send(post("/api/work-orders"), "{\"customerId\":\"" + customer + "\",\"vehicleId\":\"" + vehicle
                + "\",\"complaint\":\"Revisão hidráulica\"}").andExpect(status().isCreated()));
    }

    ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return api.send(owner, request, body);
    }

    static LocalDate today() { return LocalDate.now(WORKSHOP); }

    static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
