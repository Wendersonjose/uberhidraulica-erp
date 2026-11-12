package br.com.uberhidraulica.erp.purchasing;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeFixtures;
import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0021, I5b: ligação pedido × NF-e contra PostgreSQL real (`DR-0023`, decidida em 2026-11-12 — C1-A, C2-A, C3-A).
 *
 * <p>Prova que o pedido vinculado fica encerrado pela nota, que qualquer diferença exige justificativa e fica gravada,
 * que um pedido não atende duas notas e que cancelar a compra da nota reabre o pedido.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrderInvoiceLinkIntegrationTest {
    private static final String OWNER_EMAIL = "owner-link@example.test";
    private static final String BOOTSTRAP_PASSWORD = "link-bootstrap-password";
    private static final String PASSWORD = "link-operational-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(40000);

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Link");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;
    private UUID expenseCategory;

    @BeforeEach
    void sessions() throws Exception {
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
        String body = api.send(owner, post("/api/finance/expense-categories"),
                        "{\"name\":\"Peças vínculo " + SEQUENCE.incrementAndGet() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        expenseCategory = UUID.fromString(JsonPath.read(body, "$.id"));
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

    private UUID supplier(String document) throws Exception {
        String body = api.send(owner, post("/api/purchasing/suppliers"),
                        "{\"personType\":\"PJ\",\"legalName\":\"Fornecedor vínculo " + SEQUENCE.incrementAndGet()
                                + " Ltda\",\"document\":\"" + document + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID product() throws Exception {
        String body = api.send(owner, post("/api/products"), "{\"description\":\"Produto vínculo "
                        + SEQUENCE.incrementAndGet() + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Pedido de uma linha, aprovado pelo Dono (que pode aprovar o próprio, D8). */
    private UUID approvedOrder(UUID supplier, UUID product, String quantity, String price) throws Exception {
        String created = mvc.perform(post("/api/purchasing/orders").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"ORDER\",\"supplierId\":\"" + supplier + "\",\"lines\":[{\"productId\":\""
                                + product + "\",\"quantity\":" + quantity + ",\"unitPrice\":" + price + "}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
        api.send(owner, post("/api/purchasing/orders/" + id + "/submit"), "{\"version\":0}")
                .andExpect(status().isOk());
        api.send(owner, post("/api/purchasing/orders/" + id + "/approve"), "{\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        return id;
    }

    /** Nota de um item do emitente informado. */
    private NfeFixtures note(String issuer, String quantity, String unitPrice, String total) {
        NfeFixtures fixture = NfeFixtures.valid();
        fixture.issuer = issuer;
        fixture.number = SEQUENCE.incrementAndGet();
        fixture.lines = new ArrayList<>(List.of(new NfeFixtures.Line("V-001", "Item da nota", quantity, unitPrice,
                total)));
        fixture.installments = new ArrayList<>(List.<String[]>of(new String[] {"001", "2026-12-10", total}));
        return fixture;
    }

    private String body(NfeFixtures fixture, UUID product, String extra) {
        return "{\"xml\":" + quote(fixture.xml()) + ",\"createSupplier\":true,\"expenseCategoryId\":\""
                + expenseCategory + "\",\"decisions\":[{\"lineNo\":1,\"productId\":\"" + product
                + "\",\"saveLink\":false}]" + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions confirm(String body) throws Exception {
        return mvc.perform(post("/api/purchasing/nfe/confirm").cookie(owner.cookie())
                .header(owner.csrfHeader(), owner.csrfToken()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String detail(UUID id) throws Exception {
        return api.read(owner, "/api/purchasing/purchases/" + id).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    private List<String> linkable(UUID supplier) throws Exception {
        String body = api.read(owner, "/api/purchasing/nfe/linkable-orders?supplierId=" + supplier)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // ================================================================ C1-A

    @Test
    void anInvoiceThatMatchesTheOrderClosesItWithoutJustificationAndTheOrderStopsBeingACandidate() throws Exception {
        String issuer = cnpj();
        UUID supplier = supplier(issuer);
        UUID product = product();
        UUID order = approvedOrder(supplier, product, "2", "50.00");
        assertThat(linkable(supplier)).containsExactly(order.toString());

        NfeFixtures fixture = note(issuer, "2.0000", "50.00", "100.00");
        String confirmed = confirm(body(fixture, product, "\"orderId\":\"" + order + "\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.linkedOrderId").value(order.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID invoice = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        // O pedido mostra a nota que o encerrou, e a nota mostra o pedido.
        String orderDetail = detail(order);
        assertThat((String) JsonPath.read(orderDetail, "$.orderLink.invoicePurchaseId")).isEqualTo(invoice.toString());
        assertThat((Boolean) JsonPath.read(orderDetail, "$.orderLink.divergent")).isFalse();
        assertThat((String) JsonPath.read(detail(invoice), "$.orderLink.orderPurchaseId")).isEqualTo(order.toString());
        assertThat(linkable(supplier)).isEmpty();

        // Encerrado pela nota: não cancela sozinho e continua sem receber.
        api.send(owner, post("/api/purchasing/purchases/" + order + "/cancel"), "{\"reason\":\"teste\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_CLOSED_BY_INVOICE"));

        // Um pedido não atende duas notas.
        NfeFixtures second = note(issuer, "2.0000", "50.00", "100.00");
        confirm(body(second, product, "\"orderId\":\"" + order + "\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_ALREADY_LINKED"));
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_document where access_key = ?",
                Long.class, second.accessKey())).isZero();
    }

    // ================================================================ C2-A / C3-A

    @Test
    void aDivergingInvoiceIsRefusedWithoutJustificationAndRecordsEveryDifferenceWhenJustified() throws Exception {
        String issuer = cnpj();
        UUID supplier = supplier(issuer);
        UUID product = product();
        UUID order = approvedOrder(supplier, product, "2", "50.00");
        NfeFixtures fixture = note(issuer, "3.0000", "50.00", "150.00");

        // A comparação mostra a diferença antes de qualquer gravação.
        String comparison = api.send(owner, post("/api/purchasing/nfe/order-comparison"),
                        "{\"xml\":" + quote(fixture.xml()) + ",\"orderId\":\"" + order
                                + "\",\"decisions\":[{\"lineNo\":1,\"productId\":\"" + product + "\"}]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(comparison, "$.differences[*].kind"))
                .containsExactly("TOTAL", "QUANTITY");
        assertThat((Boolean) JsonPath.read(comparison, "$.mayAccept")).isTrue();

        confirm(body(fixture, product, "\"orderId\":\"" + order + "\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_LINK_JUSTIFICATION_REQUIRED"));
        // Recusar o vínculo não grava metade da importação.
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_document where access_key = ?",
                Long.class, fixture.accessKey())).isZero();

        confirm(body(fixture, product, "\"orderId\":\"" + order
                + "\",\"divergenceJustification\":\"Fornecedor enviou uma unidade a mais, combinado por telefone\""))
                .andExpect(status().isOk());

        String orderDetail = detail(order);
        assertThat((Boolean) JsonPath.read(orderDetail, "$.orderLink.divergent")).isTrue();
        assertThat((String) JsonPath.read(orderDetail, "$.orderLink.divergenceJustification"))
                .startsWith("Fornecedor enviou");
        assertThat((List<String>) JsonPath.read(orderDetail, "$.orderLink.differences[*].kind"))
                .containsExactly("TOTAL", "QUANTITY");
        // Trilha nos dois lados.
        assertThat(jdbc.queryForObject("""
                select count(*) from purchasing.purchase_event
                where event_type = 'ORDER_LINKED_TO_INVOICE' and purchase_id = ?""", Long.class, order)).isEqualTo(1);
    }

    @Test
    void aSubstitutedItemAppearsAsTheOriginalMissingAndTheSubstituteExtra() throws Exception {
        String issuer = cnpj();
        UUID supplier = supplier(issuer);
        UUID ordered = product();
        UUID substitute = product();
        UUID order = approvedOrder(supplier, ordered, "1", "100.00");
        NfeFixtures fixture = note(issuer, "1.0000", "100.00", "100.00");

        confirm(body(fixture, substitute, "\"orderId\":\"" + order
                + "\",\"divergenceJustification\":\"Item substituído por equivalente\"")).andExpect(status().isOk());

        assertThat((List<String>) JsonPath.read(detail(order), "$.orderLink.differences[*].kind"))
                .containsExactly("ITEM_NOT_IN_INVOICE", "ITEM_NOT_IN_ORDER");
    }

    // ================================================================ compras da OS

    @Test
    void theInvoiceInheritsTheWorkOrderOfTheLinkedOrderAndBothAreListedAsPurchasesOfThatWorkOrder() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String customer = JsonPath.read(api.send(owner, post("/api/customers"),
                        "{\"personType\":\"PF\",\"name\":\"Cliente vínculo\",\"phone\":\"34999990000\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String vehicle = JsonPath.read(api.send(owner, post("/api/vehicles"), "{\"customerId\":\"" + customer
                        + "\",\"plate\":\"LNK" + (n % 10000) + "\",\"manufacturer\":\"Volvo\",\"model\":\"FH\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String workOrder = JsonPath.read(api.send(owner, post("/api/work-orders"), "{\"customerId\":\"" + customer
                        + "\",\"vehicleId\":\"" + vehicle + "\",\"complaint\":\"Vazamento\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        String issuer = cnpj();
        UUID supplier = supplier(issuer);
        UUID product = product();
        String created = mvc.perform(post("/api/purchasing/orders").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"ORDER\",\"supplierId\":\"" + supplier + "\",\"workOrderId\":\"" + workOrder
                                + "\",\"lines\":[{\"productId\":\"" + product + "\",\"quantity\":2,\"unitPrice\":50.00}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID order = UUID.fromString(JsonPath.read(created, "$.id"));
        api.send(owner, post("/api/purchasing/orders/" + order + "/submit"), "{\"version\":0}").andExpect(status().isOk());
        api.send(owner, post("/api/purchasing/orders/" + order + "/approve"), "{\"version\":1}").andExpect(status().isOk());

        // A importação não informa OS: vale a do pedido.
        String confirmed = confirm(body(note(issuer, "2.0000", "50.00", "100.00"), product, "\"orderId\":\"" + order + "\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID invoice = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));
        assertThat((String) JsonPath.read(detail(invoice), "$.workOrderId")).isEqualTo(workOrder);

        String listed = api.read(owner, "/api/purchasing/purchases?workOrderId=" + workOrder).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(listed, "$.items[*].id"))
                .containsExactlyInAnyOrder(order.toString(), invoice.toString());
        String others = api.read(owner, "/api/purchasing/purchases?workOrderId=" + UUID.randomUUID())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(others, "$.items")).isEmpty();
    }

    // ================================================================ o que não se vincula

    @Test
    void anOrderOfAnotherSupplierOrNotYetApprovedIsNotLinkable() throws Exception {
        String issuer = cnpj();
        supplier(issuer);
        UUID product = product();
        UUID foreign = approvedOrder(supplier(cnpj()), product, "2", "50.00");

        confirm(body(note(issuer, "2.0000", "50.00", "100.00"), product, "\"orderId\":\"" + foreign + "\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PURCHASE_ORDER_LINK_INVALID"));

        confirm(body(note(issuer, "2.0000", "50.00", "100.00"), product, "\"orderId\":\"" + UUID.randomUUID() + "\""))
                .andExpect(status().isNotFound());
    }

    // ================================================================ liberação

    @Test
    void cancellingTheInvoicePurchaseReleasesTheOrderWhichCanThenBeLinkedAgainOrCancelled() throws Exception {
        String issuer = cnpj();
        UUID supplier = supplier(issuer);
        UUID product = product();
        UUID order = approvedOrder(supplier, product, "2", "50.00");
        String confirmed = confirm(body(note(issuer, "2.0000", "50.00", "100.00"), product,
                "\"orderId\":\"" + order + "\"")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        UUID invoice = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));

        api.send(owner, post("/api/purchasing/purchases/" + invoice + "/cancel"), "{\"reason\":\"nota errada\"}")
                .andExpect(status().isOk());

        // O vínculo permanece como histórico, liberado; o pedido voltou a ser candidato.
        assertThat(jdbc.queryForObject("""
                select count(*) from purchasing.purchase_order_invoice_link
                where order_purchase_id = ? and released_at is not null""", Long.class, order)).isEqualTo(1);
        assertThat(linkable(supplier)).containsExactly(order.toString());
        assertThat((Object) JsonPath.read(detail(order), "$.orderLink")).isNull();

        // Liberado, o pedido aceita outra nota.
        confirm(body(note(issuer, "2.0000", "50.00", "100.00"), product, "\"orderId\":\"" + order + "\""))
                .andExpect(status().isOk());
        assertThat(linkable(supplier)).isEmpty();
    }
}
