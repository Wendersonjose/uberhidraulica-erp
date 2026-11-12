package br.com.uberhidraulica.erp.purchasing;

import br.com.uberhidraulica.erp.support.ApiSessions;
import br.com.uberhidraulica.erp.support.ApiSessions.Session;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aceitação com a <b>NF-e nº 21 real</b> da oficina, fornecida pelo proprietário.
 *
 * <p>O arquivo em {@code src/test/resources/nfe/nfe-21-real-redigida.xml} é o documento verdadeiro com uma
 * única alteração: o conteúdo de {@code <SignatureValue>} e {@code <X509Certificate>} foi substituído por um
 * marcador. Esses dois campos carregam o certificado e dados pessoais (nome, CPF e e-mail do responsável), que
 * não devem ser versionados. A <b>estrutura</b> da assinatura foi preservada, para o teste exercitar o mesmo
 * caminho da nota real — e a validade criptográfica da assinatura não é verificada nesta Task de qualquer
 * forma (`DR-0022` D12), portanto nada de valor de teste se perde.</p>
 *
 * <p>Todo o resto é idêntico: chave de acesso, emitente, destinatário, os 12 itens, os totais, o protocolo de
 * autorização e as três duplicatas.</p>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
// A nota real tem UMA chave de acesso, e a D5 permite exatamente uma compra ativa por chave. A ordem é,
// portanto, inerente ao cenário e não uma dependência acidental: as leituras acontecem antes da importação, e
// a importação acontece uma única vez, provando a recusa da segunda no mesmo teste.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealNfe21AcceptanceTest {
    private static final String OWNER_EMAIL = "owner-real-nfe@example.test";
    private static final String BOOTSTRAP_PASSWORD = "real-nfe-bootstrap-password";
    private static final String PASSWORD = "real-nfe-operational-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(9000);

    private static final String ACCESS_KEY = "31261067757350000178550010000000211010202158";
    private static final String ISSUER = "67757350000178";
    private static final String RECIPIENT = "01637003000101";
    private static final String INVOICE_TOTAL = "1943.10";

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Real Nfe");
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
        expenseCategory = expenseCategory();
    }

    /** As 12 linhas reais da nota, na ordem: código do fornecedor, unidade comercial e valor. */
    private static List<String[]> realLines() {
        return List.of(
                new String[]{"70893E19BR", "un", "220.00"},
                new String[]{"W7122", "un", "14.00"},
                new String[]{"75410", "un", "50.00"},
                new String[]{"CT874K3", "un", "225.61"},
                new String[]{"ST-10100149", "KIT", "195.98"},
                new String[]{"ST-10100038", "KIT", "251.19"},
                new String[]{"KIT-000030", "KIT", "41.20"},
                new String[]{"KIT-000074", "UN", "214.07"},
                new String[]{"KIT-000093", "UN", "25.82"},
                new String[]{"KIT-000033", "KIT", "241.93"},
                new String[]{"10-3004", "un", "175.00"},
                new String[]{"90000460", "un", "288.30"});
    }

    private static byte[] realXml() throws Exception {
        try (var stream = RealNfe21AcceptanceTest.class.getResourceAsStream("/nfe/nfe-21-real-redigida.xml")) {
            assertThat(stream).as("XML real da NF-e 21 em src/test/resources/nfe").isNotNull();
            return stream.readAllBytes();
        }
    }

    private UUID expenseCategory() throws Exception {
        String body = api.send(owner, post("/api/finance/expense-categories"),
                        "{\"name\":\"Peças NF21 " + SEQUENCE.incrementAndGet() + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID product(String description) throws Exception {
        String body = api.send(owner, post("/api/products"),
                        "{\"description\":\"" + description + "\",\"type\":\"PART\",\"unit\":\"UNIDADE\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String preview() throws Exception {
        return mvc.perform(post("/api/purchasing/nfe/preview").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken())
                        .contentType(MediaType.APPLICATION_XML).content(realXml()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ================================================================ prévia

    @Test
    @Order(1)
    void theRealNoteIsReadWithItsTwelveItemsTotalAndAuthorizedRecipient() throws Exception {
        String body = preview();

        assertThat((String) JsonPath.read(body, "$.accessKey")).isEqualTo(ACCESS_KEY);
        assertThat((String) JsonPath.read(body, "$.issuerDocument")).isEqualTo(ISSUER);
        assertThat((String) JsonPath.read(body, "$.recipientDocument")).isEqualTo(RECIPIENT);
        assertThat(JsonPath.read(body, "$.invoiceNumber").toString()).isEqualTo("21");
        assertThat((String) JsonPath.read(body, "$.issuedOn")).isEqualTo("2026-10-09");
        assertThat((List<?>) JsonPath.read(body, "$.items")).hasSize(12);

        // D9: o destinatário é o estabelecimento autorizado, com o CNPJ que o proprietário informou.
        assertThat((String) JsonPath.read(body, "$.recipientCheck")).isEqualTo("AUTHORIZED");

        assertThat(new BigDecimal(JsonPath.read(body, "$.totals.invoice").toString()))
                .isEqualByComparingTo(INVOICE_TOTAL);
        assertThat(new BigDecimal(JsonPath.read(body, "$.totals.itemsSum").toString()))
                .isEqualByComparingTo(INVOICE_TOTAL);
        assertThat((Boolean) JsonPath.read(body, "$.totals.closes")).isTrue();

        // A nota é do Simples Nacional (CSOSN 102/500): sem frete, sem IPI e sem ICMS-ST.
        assertThat(new BigDecimal(JsonPath.read(body, "$.totals.freight").toString())).isEqualByComparingTo("0.00");
        assertThat(new BigDecimal(JsonPath.read(body, "$.totals.taxAdded").toString())).isEqualByComparingTo("0.00");
    }

    @Test
    @Order(2)
    void theThreeRealDuplicatesAreReadExactlyAsTheNoteStatesThem() throws Exception {
        String body = preview();

        assertThat((Boolean) JsonPath.read(body, "$.installmentsFromInvoice")).isTrue();
        assertThat((List<?>) JsonPath.read(body, "$.installments")).hasSize(3);
        assertThat(JsonPath.read(body, "$.installments[*].dueDate").toString())
                .contains("2026-10-23", "2026-10-30", "2026-11-06");
        assertThat(JsonPath.read(body, "$.installments[*].reference").toString())
                .contains("001", "002", "003");
        List<?> amounts = JsonPath.read(body, "$.installments[*].amount");
        assertThat(amounts).hasSize(3);
        assertThat(amounts.stream().map(a -> new BigDecimal(a.toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(INVOICE_TOTAL);
        amounts.forEach(a -> assertThat(new BigDecimal(a.toString())).isEqualByComparingTo("647.70"));
    }

    @Test
    @Order(3)
    void theNoteIsAuthorizedButTheOfficialCheckAndTheSignatureAreExplicitlyNotVerified() throws Exception {
        String body = preview();
        String issues = JsonPath.read(body, "$.issues[*].code").toString();

        // A estrutura da assinatura foi preservada no arquivo redigido, então este é o caminho da nota real.
        assertThat(issues).contains("NFE_SIGNATURE_NOT_VERIFIED", "NFE_OFFICIAL_VALIDATION_NOT_PERFORMED");
        assertThat(issues).doesNotContain("NFE_SIGNATURE_ABSENT");
        assertThat((Boolean) JsonPath.read(body, "$.officialCheckPerformed")).isFalse();
    }

    @Test
    @Order(4)
    void theIssuerIsADistinctLegalEntityFromTheWorkshopEvenThoughTheNamesLookAlike() throws Exception {
        String body = preview();

        // "UBERHIDRAULICA PECAS LTDA" (67757350) e "UBER-HIDRAULICA LTDA" (01637003) têm raízes de CNPJ
        // diferentes. Pela D9 endurecida, parecença de nome e endereço igual não são prova de identidade: a
        // nota é de pessoa jurídica distinta, e é por isso que ela pode ser fornecedora.
        assertThat((String) JsonPath.read(body, "$.supplier.document")).isEqualTo(ISSUER);
        assertThat((String) JsonPath.read(body, "$.supplier.legalName")).contains("UBERHIDRAULICA PECAS");
        assertThat(ISSUER.substring(0, 8)).isNotEqualTo(RECIPIENT.substring(0, 8));
    }

    /**
     * O achado operacional desta nota: quatro itens vêm com {@code uCom = KIT}, que a `D7` aprovada lista
     * entre as unidades que <b>exigem</b> fator de conversão. Não há equivalência automática — seria sobrepor
     * a decisão do proprietário. Oito itens vêm em UN/un e dispensam fator.
     */
    @Test
    @Order(5)
    void fourOfTheTwelveRealItemsDemandAConversionFactorBecauseTheyComeAsKit() throws Exception {
        String body = preview();

        // Sem produto vinculado ainda, nenhum item é confirmável; o que importa aqui é a unidade lida.
        List<String> units = JsonPath.read(body, "$.items[*].unitOriginal");
        assertThat(units).hasSize(12);
        assertThat(units.stream().filter(u -> "KIT".equalsIgnoreCase(u)).count()).isEqualTo(4L);
        assertThat(units.stream().filter(u -> "UN".equalsIgnoreCase(u)).count()).isEqualTo(8L);

        assertThat((Boolean) JsonPath.read(body, "$.confirmable")).isFalse();
        assertThat(JsonPath.read(body, "$.blockers").toString()).contains("não está vinculado");
    }

    @Test
    @Order(6)
    void theRealGtinsAreReadAndSemGtinBecomesAbsentInsteadOfLiteralText() throws Exception {
        String body = preview();
        List<String> gtins = JsonPath.read(body, "$.items[*].gtin");

        assertThat(gtins).contains("7805670655986", "7909303000539", "7898587461504", "7892677007900");
        assertThat(gtins).doesNotContain("SEM GTIN");
    }

    // ================================================================ confirmação

    @Test
    @Order(7)
    void theRealNoteImportsIntoOnePurchaseWithTwelveItemsAndThreeOpenPayables() throws Exception {
        Map<Integer, UUID> products = new LinkedHashMap<>();
        List<String> decisions = new ArrayList<>();
        List<String[]> lines = realLines();
        for (int i = 0; i < lines.size(); i++) {
            String[] line = lines.get(i);
            UUID productId = product(line[0] + " " + SEQUENCE.incrementAndGet());
            products.put(i + 1, productId);
            // Unidade KIT exige fator; aqui o kit é comprado e estocado como uma unidade, então o fator é 1 —
            // informado explicitamente, como a D7 manda, nunca presumido pelo sistema.
            String factor = "KIT".equalsIgnoreCase(line[1]) ? ",\"unitConversionFactor\":1" : "";
            decisions.add("{\"lineNo\":" + (i + 1) + ",\"productId\":\"" + productId + "\"" + factor
                    + ",\"saveLink\":true}");
        }

        String body = "{\"xml\":" + quote(new String(realXml(), StandardCharsets.UTF_8))
                + ",\"createSupplier\":true,\"expenseCategoryId\":\"" + expenseCategory
                + "\",\"decisions\":[" + String.join(",", decisions) + "]}";

        String confirmed = mvc.perform(post("/api/purchasing/nfe/confirm").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(12))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();

        UUID purchaseId = UUID.fromString(JsonPath.read(confirmed, "$.purchaseId"));
        assertThat(new BigDecimal(JsonPath.read(confirmed, "$.payableTotal").toString()))
                .isEqualByComparingTo(INVOICE_TOTAL);

        // Três contas a pagar, em aberto, somando exatamente o total da nota.
        assertThat(jdbc.queryForObject("select count(*) from purchasing.purchase_payable where purchase_id = ?",
                Long.class, purchaseId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select sum(amount) from purchasing.purchase_payable where purchase_id = ?",
                BigDecimal.class, purchaseId)).isEqualByComparingTo(INVOICE_TOTAL);
        assertThat(jdbc.queryForObject("""
                select count(*) from finance.payable p join purchasing.purchase_payable pp on pp.payable_id = p.id
                where pp.purchase_id = ? and exists (select 1 from finance.payable_payment s where s.payable_id = p.id)
                """, Long.class, purchaseId)).isZero();

        // D1 na nota do Simples: sem IPI, sem ICMS-ST e sem frete, o custo é o valor do produto. A soma dos
        // custos de aquisição dos 12 itens fecha com o total da nota.
        assertThat(jdbc.queryForObject(
                "select sum(acquisition_total) from purchasing.purchase_item where purchase_id = ?",
                BigDecimal.class, purchaseId)).isEqualByComparingTo(INVOICE_TOTAL);
        assertThat(jdbc.queryForObject("""
                select count(*) from purchasing.purchase_item
                where purchase_id = ? and (cost_ipi_amount <> 0 or cost_icms_st_amount <> 0 or cost_freight_share <> 0)
                """, Long.class, purchaseId)).isZero();

        // Importar não movimenta estoque: a entrada física é o recebimento (I4).
        assertThat(jdbc.queryForObject("""
                select count(*) from inventory.stock_movement m
                where m.product_id in (select product_id from purchasing.purchase_item where purchase_id = ?)
                """, Long.class, purchaseId)).isZero();

        // O documento guarda a chave e o protocolo reais, e o XML fica só nele.
        assertThat(jdbc.queryForObject(
                "select access_key from purchasing.purchase_document where purchase_id = ?", String.class,
                purchaseId)).isEqualTo(ACCESS_KEY);
        assertThat(jdbc.queryForObject(
                "select protocol_number from purchasing.purchase_document where purchase_id = ?", String.class,
                purchaseId)).isEqualTo("131267983727896");
        assertThat(jdbc.queryForObject("""
                select count(*) from purchasing.purchase_document where purchase_id = ? and xml_content is not null
                """, Long.class, purchaseId)).isEqualTo(1L);

        // D5, no mesmo teste porque a nota real tem uma única chave: a segunda importação é recusada mesmo
        // com chave de idempotência nova, e continua existindo exatamente um documento ativo.
        mvc.perform(post("/api/purchasing/nfe/confirm").cookie(owner.cookie())
                        .header(owner.csrfHeader(), owner.csrfToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_DUPLICATE_NFE"));
        assertThat(jdbc.queryForObject("""
                select count(*) from purchasing.purchase_document where access_key = ? and voided_at is null
                """, Long.class, ACCESS_KEY)).isEqualTo(1L);
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
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
