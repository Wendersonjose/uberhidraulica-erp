package br.com.uberhidraulica.erp.purchasing;

import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0021, I5: pedido de compra e compra direta contra PostgreSQL real — rascunho, aprovação com segregação de
 * funções (DR-0022 D8), controle otimista e a prova de que pedido não paga nem movimenta estoque.
 *
 * <p>Cada cenário cria fornecedor e produtos próprios, então a classe não depende de limpeza entre testes.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseOrderIntegrationTest {
    private static final String OWNER_EMAIL = "owner-order@example.test";
    private static final String BOOTSTRAP_PASSWORD = "order-bootstrap-password";
    private static final String PASSWORD = "order-operational-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(20000);

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Order");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;
    private Session admin;
    private Session otherAdmin;
    private Session finance;

    @BeforeEach
    void sessions() throws Exception {
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
        admin = api.user(owner, "admin-order@example.test", "GERENTE_ADMINISTRATIVO");
        otherAdmin = api.user(owner, "admin2-order@example.test", "GERENTE_ADMINISTRATIVO");
        finance = api.user(owner, "finance-order@example.test", "GERENTE_FINANCEIRO");
    }

    // ================================================================ apoio

    private static String cnpj() {
        String base = String.format("%08d0001", SEQUENCE.incrementAndGet());
        int d1 = digit(base, new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = digit(base + d1, new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    private static int digit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }

    private UUID supplier() throws Exception {
        String body = api.send(owner, post("/api/purchasing/suppliers"), "{\"personType\":\"PJ\",\"legalName\":\"Fornecedor pedido "
                + SEQUENCE.incrementAndGet() + " Ltda\",\"document\":\"" + cnpj() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID product() throws Exception {
        String body = api.send(owner, post("/api/products"), "{\"description\":\"Produto pedido " + SEQUENCE.incrementAndGet()
                + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static String lineJson(UUID product, String quantity, String price) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + quantity + ",\"unitPrice\":" + price + "}";
    }

    private static String createBody(String origin, UUID supplier, String freight, String... lines) {
        return "{\"origin\":\"" + origin + "\",\"supplierId\":\"" + supplier + "\""
                + (freight == null ? "" : ",\"freight\":" + freight) + ",\"lines\":[" + String.join(",", lines) + "]}";
    }

    private static String updateBody(long version, UUID supplier, String freight, String... lines) {
        return "{\"version\":" + version + ",\"supplierId\":\"" + supplier + "\""
                + (freight == null ? "" : ",\"freight\":" + freight) + ",\"lines\":[" + String.join(",", lines) + "]}";
    }

    private ResultActions create(Session as, String key, String body) throws Exception {
        var request = post("/api/purchasing/orders").cookie(as.cookie()).header(as.csrfHeader(), as.csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private ResultActions act(Session as, UUID id, String action, String body) throws Exception {
        return api.send(as, post("/api/purchasing/orders/" + id + "/" + action), body);
    }

    private ResultActions edit(Session as, UUID id, String body) throws Exception {
        return api.send(as, put("/api/purchasing/orders/" + id), body);
    }

    private static UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private String detail(UUID id) throws Exception {
        return api.read(owner, "/api/purchasing/purchases/" + id).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    private static long versionOf(String detail) {
        return ((Number) JsonPath.read(detail, "$.version")).longValue();
    }

    /** Cria um pedido de uma linha (5 × 20,00) e devolve seu id. */
    private UUID draftOrder(Session as) throws Exception {
        return idOf(create(as, UUID.randomUUID().toString(), createBody("ORDER", supplier(), null,
                lineJson(product(), "5", "20.00"))).andExpect(status().isCreated()));
    }

    private UUID submitted(Session author) throws Exception {
        UUID id = draftOrder(author);
        act(author, id, "submit", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        return id;
    }

    private BigDecimal stock(UUID productId) {
        List<BigDecimal> rows = jdbc.queryForList("select quantity from inventory.stock_balance where product_id = ?",
                BigDecimal.class, productId);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    // ================================================================ fluxo completo

    @Test
    void anOrderWalksTheWholeFlowWithoutTouchingStockOrPayables() throws Exception {
        UUID supplier = supplier();
        UUID first = product();
        UUID second = product();

        // Criação: rascunho, versão 0, total = itens + frete. Nada de conta a pagar nem de estoque.
        UUID id = idOf(create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, "10.00",
                lineJson(first, "10", "10.00"), lineJson(second, "5", "10.00")))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.origin").value("ORDER"))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.payableTotal").value(160.0))
                .andExpect(jsonPath("$.installments.length()").value(0)));
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_payable where purchase_id = ?", Long.class,
                id)).isZero();

        // O custo de aquisição do item já leva o rateio do frete (D1/D2): 100,00 + 6,67 = 106,67 → 10,6670/un.
        String created = detail(id);
        assertThat(new BigDecimal(String.valueOf((Object) JsonPath.read(created, "$.items[0].unitCost"))))
                .isEqualByComparingTo("10.6670");

        // Edição do rascunho com a versão certa troca os itens e recalcula; com a versão velha, conflito.
        edit(admin, id, updateBody(0, supplier, "10.00", lineJson(first, "10", "10.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.payableTotal").value(110.0));
        edit(admin, id, updateBody(0, supplier, "10.00", lineJson(first, "10", "10.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_VERSION_CONFLICT"));
        // Volta a duas linhas para o recebimento abaixo.
        edit(admin, id, updateBody(1, supplier, "10.00", lineJson(first, "10", "10.00"), lineJson(second, "5", "10.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        // Envio: deixa de ser editável.
        act(admin, id, "submit", "{\"version\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL")).andExpect(jsonPath("$.version").value(3));
        edit(admin, id, updateBody(3, supplier, "10.00", lineJson(first, "1", "1.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_EDITABLE"));
        act(admin, id, "submit", "{\"version\":3}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_EDITABLE"));

        // D8: quem criou não aprova (GERENTE_ADMINISTRATIVO não tem a exceção).
        act(admin, id, "approve", "{\"version\":3}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURCHASE_APPROVAL_SELF_FORBIDDEN"));
        assertThat(detail(id)).contains("\"status\":\"AWAITING_APPROVAL\"");
        // Outra pessoa, vendo uma versão velha, não aprova o que não viu.
        act(otherAdmin, id, "approve", "{\"version\":2}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_VERSION_CONFLICT"));
        // Com a versão certa, aprova — e o aprovador fica registrado.
        act(otherAdmin, id, "approve", "{\"version\":3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.approvedBy").isNotEmpty()).andExpect(jsonPath("$.approvedAt").isNotEmpty());

        // Aprovar o pedido NÃO mexeu em estoque nem em financeiro.
        assertThat(stock(first)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_payable where purchase_id = ?", Long.class,
                id)).isZero();

        // DR-0023: aprovado, o pedido AINDA NÃO recebe. Sem ligação com a NF-e que o fornecedor emitirá, receber aqui e
        // de novo na compra da nota lançaria o mesmo material duas vezes. A trava fecha o caminho sem identidade.
        String item = JsonPath.read(detail(id), "$.items[0].id");
        api.send(otherAdmin, post("/api/purchasing/purchases/" + id + "/receipts").header("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"lines\":[{\"purchaseItemId\":\"" + item + "\",\"quantity\":4}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_REQUIRES_INVOICE"));
        assertThat(stock(first)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where purchase_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movement where product_id = ?", Long.class, first)).isZero();

        // Sem recebimento ativo, o pedido aprovado ainda pode ser cancelado; a trilha conta a história na ordem em que ocorreu.
        api.send(owner, post("/api/purchasing/purchases/" + id + "/cancel"), "{\"reason\":\"Desistência\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        List<String> events = JsonPath.read(api.read(owner, "/api/purchasing/purchases/" + id + "/events?size=50")
                .andReturn().getResponse().getContentAsString(), "$.items[*].type");
        assertThat(events).contains("CREATED", "ORDER_UPDATED", "ORDER_SUBMITTED", "ORDER_APPROVED", "CANCELLED")
                .doesNotContain("RECEIPT_REGISTERED");
    }

    @Test
    void neitherAnApprovedOrderNorADirectPurchaseReceivesBecauseOnlyTheInvoiceHasAnIdentity() throws Exception {
        for (String origin : List.of("ORDER", "DIRECT")) {
            UUID id = idOf(create(admin, UUID.randomUUID().toString(), createBody(origin, supplier(), null,
                    lineJson(product(), "3", "9.90"))).andExpect(status().isCreated()));
            act(admin, id, "submit", "{\"version\":0}").andExpect(status().isOk());
            act(otherAdmin, id, "approve", "{\"version\":1}").andExpect(status().isOk());
            String item = JsonPath.read(detail(id), "$.items[0].id");
            UUID product = UUID.fromString(JsonPath.read(detail(id), "$.items[0].productId"));

            // A recusa é por origem e vale com ou sem a mesma chave de idempotência, para qualquer perfil que receba.
            for (Session receiver : List.of(owner, otherAdmin)) {
                api.send(receiver, post("/api/purchasing/purchases/" + id + "/receipts").header("Idempotency-Key", UUID.randomUUID().toString()),
                        "{\"lines\":[{\"purchaseItemId\":\"" + item + "\",\"quantity\":3}]}")
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PURCHASE_RECEIPT_REQUIRES_INVOICE"));
            }
            assertThat(stock(product)).as("estoque após tentar receber " + origin).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("select count(*) from purchasing.goods_receipt where purchase_id = ?", Long.class, id)).isZero();
        }
    }

    // ================================================================ D8

    @Test
    void theOwnerMayApproveTheirOwnOrderAndTheTrailSaysSo() throws Exception {
        UUID id = submitted(owner);

        act(owner, id, "approve", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        String payload = jdbc.queryForObject("""
                select payload::text from purchasing.purchase_event where purchase_id = ? and event_type = 'ORDER_APPROVED'
                """, String.class, id);
        assertThat(payload).contains("\"ownApproval\": \"true\"");
    }

    @Test
    void theExceptionIsAPermissionNotAProfileNameSoTheOwnerCanGrantItWithoutADeploy() throws Exception {
        assertThat(jdbc.queryForObject("""
                select count(*) from iam.profile_permission pp join iam.profile p on p.id = pp.profile_id
                join iam.permission perm on perm.id = pp.permission_id
                where perm.code = 'PURCHASE_APPROVE_OWN' and p.code = 'DONO'""", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from iam.profile_permission pp join iam.profile p on p.id = pp.profile_id
                join iam.permission perm on perm.id = pp.permission_id
                where perm.code = 'PURCHASE_APPROVE_OWN' and p.code <> 'DONO'""", Long.class)).isZero();
    }

    @Test
    void returningAnOrderNeedsAReasonAndMakesItEditableAgain() throws Exception {
        UUID id = submitted(admin);

        act(otherAdmin, id, "return", "{\"version\":1,\"reason\":\"  \"}").andExpect(status().isBadRequest());
        act(otherAdmin, id, "return", "{\"version\":1,\"reason\":\"Preço acima do combinado\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(2));

        String type = JsonPath.read(api.read(owner, "/api/purchasing/purchases/" + id + "/events?size=50")
                .andReturn().getResponse().getContentAsString(), "$.items[0].type");
        assertThat(type).isEqualTo("ORDER_RETURNED");
        String reason = JsonPath.read(api.read(owner, "/api/purchasing/purchases/" + id + "/events?size=50")
                .andReturn().getResponse().getContentAsString(), "$.items[0].reason");
        assertThat(reason).isEqualTo("Preço acima do combinado");

        edit(admin, id, updateBody(2, supplier(), null, lineJson(product(), "2", "3.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void approvalAndReturnNeedAnOrderAwaitingApproval() throws Exception {
        UUID draft = draftOrder(admin);

        act(otherAdmin, draft, "approve", "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_AWAITING_APPROVAL"));
        act(otherAdmin, draft, "return", "{\"version\":0,\"reason\":\"não está em aprovação\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_AWAITING_APPROVAL"));
    }

    // ================================================================ permissões

    @Test
    void permissionsAreEnforcedByTheBackendNotByTheScreen() throws Exception {
        UUID supplier = supplier();
        UUID product = product();
        // GERENTE_FINANCEIRO consulta (PURCHASE_VIEW), mas não cria, não envia e não aprova.
        create(finance, UUID.randomUUID().toString(), createBody("ORDER", supplier, null, lineJson(product, "1", "1.00")))
                .andExpect(status().isForbidden());
        UUID id = submitted(admin);
        api.read(finance, "/api/purchasing/purchases/" + id).andExpect(status().isOk());
        act(finance, id, "approve", "{\"version\":1}").andExpect(status().isForbidden());
        act(finance, id, "return", "{\"version\":1,\"reason\":\"sem permissão\"}").andExpect(status().isForbidden());
        edit(finance, id, updateBody(1, supplier, null, lineJson(product, "1", "1.00"))).andExpect(status().isForbidden());

        mvc.perform(post("/api/purchasing/orders").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("ORDER", supplier, null, lineJson(product, "1", "1.00")))).andExpect(status().isUnauthorized());
        assertThat(detail(id)).contains("\"status\":\"AWAITING_APPROVAL\"");
    }

    // ================================================================ idempotência

    @Test
    void retryingTheCreationWithTheSameKeyReturnsTheSameOrder() throws Exception {
        UUID supplier = supplier();
        String body = createBody("DIRECT", supplier, null, lineJson(product(), "2", "7.50"));
        String key = UUID.randomUUID().toString();

        UUID first = idOf(create(admin, key, body).andExpect(status().isCreated()));
        UUID again = idOf(create(admin, key, body).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true")));

        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase where idempotency_key = ?", Long.class,
                key)).isEqualTo(1L);
        // A chave não vale para outro fornecedor, nem para outra pessoa: devolver o pedido alheio vazaria dado.
        create(admin, key, createBody("DIRECT", supplier(), null, lineJson(product(), "2", "7.50")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_IDEMPOTENCY_KEY_REUSED"));
        create(otherAdmin, key, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_IDEMPOTENCY_KEY_REUSED"));
        create(admin, null, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_IDEMPOTENCY_KEY_REQUIRED"));
    }

    // ================================================================ validação

    @Test
    void invalidOrdersAreRefusedWithStableCodes() throws Exception {
        UUID supplier = supplier();
        UUID product = product();
        String ok = lineJson(product, "1", "1.00");

        create(admin, UUID.randomUUID().toString(), createBody("NFE_IMPORT", supplier, null, ok))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_ORIGIN_INVALID"));
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", UUID.randomUUID(), null, ok))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_SUPPLIER_UNRESOLVED"));
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, null, lineJson(UUID.randomUUID(), "1", "1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_LINE_INVALID"));
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, null, lineJson(product, "0", "1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_LINE_INVALID"));
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, "-1.00", ok)).andExpect(status().isBadRequest());
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, null))
                .andExpect(status().isBadRequest());
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, null, lineJson(product, "1", "0.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_TOTAL_INVALID"));
        create(admin, UUID.randomUUID().toString(), "{\"origin\":\"ORDER\",\"supplierId\":\"" + supplier
                + "\",\"workOrderId\":\"" + UUID.randomUUID() + "\",\"lines\":[" + ok + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_WORK_ORDER_INVALID"));
    }

    @Test
    void anInactiveSupplierOrProductDoesNotBuy() throws Exception {
        UUID supplier = supplier();
        UUID product = product();
        api.send(owner, post("/api/purchasing/suppliers/" + supplier + "/inactivate"), "{\"reason\":\"Parou de atender\"}")
                .andExpect(status().isOk());
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, null, lineJson(product, "1", "1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_SUPPLIER_INACTIVE"));

        UUID activeSupplier = supplier();
        api.send(owner, put("/api/products/" + product), "{\"description\":\"Produto desativado\",\"type\":\"PART\",\"unit\":\"UNIDADE\","
                + "\"active\":false}").andExpect(status().isOk());
        create(admin, UUID.randomUUID().toString(), createBody("ORDER", activeSupplier, null, lineJson(product, "1", "1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_ITEM_PRODUCT_INACTIVE"));
    }

    @Test
    void aSupplierDeactivatedWhileTheOrderWaitsBlocksTheApproval() throws Exception {
        UUID supplier = supplier();
        UUID id = idOf(create(admin, UUID.randomUUID().toString(), createBody("ORDER", supplier, null,
                lineJson(product(), "1", "9.90"))).andExpect(status().isCreated()));
        act(admin, id, "submit", "{\"version\":0}").andExpect(status().isOk());
        api.send(owner, post("/api/purchasing/suppliers/" + supplier + "/inactivate"), "{\"reason\":\"Parou de atender\"}")
                .andExpect(status().isOk());

        act(otherAdmin, id, "approve", "{\"version\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_SUPPLIER_INACTIVE"));
        assertThat(detail(id)).contains("\"status\":\"AWAITING_APPROVAL\"");
    }

    // ================================================================ cancelamento e banco

    @Test
    void aDraftCanBeCancelledKeepingItsHistoryAndItIsNotReceivable() throws Exception {
        UUID id = draftOrder(admin);

        api.send(owner, post("/api/purchasing/purchases/" + id + "/cancel"), "{\"reason\":\"Compra desistida\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        edit(admin, id, updateBody(0, supplier(), null, lineJson(product(), "1", "1.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_EDITABLE"));
        api.send(otherAdmin, post("/api/purchasing/purchases/" + id + "/receipts").header("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"lines\":[{\"purchaseItemId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_RECEIVABLE"));
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_item where purchase_id = ?", Long.class,
                id)).isEqualTo(1L);
    }

    @Test
    void theDatabaseOnlyLetsItemsBeDeletedWhileThePurchaseIsADraft() throws Exception {
        UUID draft = draftOrder(admin);
        UUID waiting = submitted(admin);

        assertThatThrownBy(() -> jdbc.update("delete from purchasing.purchase_item where purchase_id = ?", waiting))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("purchasing_record_is_append_only");
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_item where purchase_id = ?", Long.class,
                waiting)).isEqualTo(1L);

        // O rascunho ainda é editável por SQL porque editá-lo é trocar itens; a compra em si nunca é apagada.
        assertThat(jdbc.update("delete from purchasing.purchase_item where purchase_id = ?", draft)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("delete from purchasing.purchase where id = ?", draft))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theApprovalPairIsConsistentInTheDatabase() throws Exception {
        UUID id = draftOrder(admin);

        assertThatThrownBy(() -> jdbc.update("update purchasing.purchase set approved_at = now() where id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    // ================================================================ concorrência

    @Test
    void twoSimultaneousApprovalsOfTheSameOrderLetExactlyOnePass() throws Exception {
        UUID id = submitted(admin);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Session approver : List.of(otherAdmin, owner)) {
                Callable<Integer> call = () -> {
                    start.await();
                    return act(approver, id, "approve", "{\"version\":1}").andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> f : results) statuses.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_event where purchase_id = ? and event_type = 'ORDER_APPROVED'",
                Long.class, id)).isEqualTo(1L);
    }

    @Test
    void anUnknownOrderIsNotFoundInsteadOfFailing() throws Exception {
        edit(admin, UUID.randomUUID(), updateBody(0, supplier(), null, lineJson(product(), "1", "1.00")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
        mvc.perform(get("/api/purchasing/purchases/" + UUID.randomUUID()).cookie(owner.cookie())).andExpect(status().isNotFound());
    }
}
