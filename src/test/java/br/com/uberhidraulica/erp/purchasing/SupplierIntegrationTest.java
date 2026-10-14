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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-0021, I2: fornecedores contra PostgreSQL real. Cada teste usa documentos próprios e únicos, então não depende
 * de limpeza: fornecedor e evento não podem ser apagados nem pelo banco de testes.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SupplierIntegrationTest {
    private static final String OWNER_EMAIL = "owner-suppliers@example.test";
    private static final String BOOTSTRAP_PASSWORD = "suppliers-bootstrap-password";
    private static final String PASSWORD = "suppliers-operational-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry properties) {
        properties.add("IAM_BOOTSTRAP_OWNER_NAME", () -> "Owner Suppliers");
        properties.add("IAM_BOOTSTRAP_OWNER_EMAIL", () -> OWNER_EMAIL);
        properties.add("IAM_BOOTSTRAP_OWNER_PASSWORD", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private ApiSessions api;
    private Session owner;
    private Session admin;
    private Session finance;

    @BeforeEach
    void sessions() throws Exception {
        api = new ApiSessions(mvc, PASSWORD);
        owner = api.owner(OWNER_EMAIL, BOOTSTRAP_PASSWORD);
        admin = api.user(owner, "admin-suppliers@example.test", "GERENTE_ADMINISTRATIVO");
        finance = api.user(owner, "finance-suppliers@example.test", "GERENTE_FINANCEIRO");
    }

    // ------------------------------------------------------------------ dados de teste

    /**
     * CPF válido e único por chamada. Documento fixo não serve: a classe não limpa fornecedores entre
     * testes e o índice único global faria o segundo teste a usar o mesmo CPF receber 409.
     */
    private static String cpf() {
        String base = String.format("%09d", SEQUENCE.incrementAndGet());
        int d1 = digit(base, new int[] {10, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = digit(base + d1, new int[] {11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    /** CNPJ numérico válido e único por chamada (raiz sequencial + dígitos verificadores calculados). */
    private static String cnpj() {
        String base = String.format("%08d0001", SEQUENCE.incrementAndGet());
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int d1 = digit(base, w1);
        int d2 = digit(base + d1, w2);
        return base + d1 + d2;
    }

    private static int digit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (digits.charAt(i) - '0') * weights[i];
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }

    private static String body(String name, String personType, String document, Long version) {
        return "{\"personType\":\"" + personType + "\",\"legalName\":\"" + name + "\""
                + (document == null ? "" : ",\"document\":\"" + document + "\"")
                + ",\"email\":\"vendas@exemplo.test\",\"address\":{\"zipCode\":\"38400-000\",\"city\":\"Uberlândia\",\"state\":\"MG\"}"
                + ",\"commercialTerms\":{\"paymentTerms\":\"28/35/42 dias\",\"usualDueDay\":10,\"creditLimit\":1500.50}"
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    private String create(Session as, String name, String personType, String document) throws Exception {
        ResultActions result = api.send(as, post("/api/purchasing/suppliers"), body(name, personType, document, null)).andExpect(status().isCreated());
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** Lista com parâmetros de consulta passados como pares nome/valor, sem depender de codificação manual de URL. */
    private String listing(Session as, String... pairs) throws Exception {
        var request = get("/api/purchasing/suppliers").cookie(as.cookie());
        for (int i = 0; i < pairs.length; i += 2) request.param(pairs[i], pairs[i + 1]);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String read(Session as, String path) throws Exception {
        return api.read(as, path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ cadastro e permissões

    @Test
    void ownerRegistersASupplierAndTheAuditTrailRecordsWhoDidIt() throws Exception {
        String document = cnpj();
        ResultActions created = api.send(owner, post("/api/purchasing/suppliers"), body("Fornecedor Alfa Ltda", "PJ", document, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document").value(document))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.address.zipCode").value("38400000"))
                .andExpect(jsonPath("$.commercialTerms.creditLimit").value(1500.50));
        String id = JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");

        String events = read(owner, "/api/purchasing/suppliers/" + id + "/events");
        assertThat((Integer) JsonPath.read(events, "$.totalItems")).isEqualTo(1);
        assertThat((String) JsonPath.read(events, "$.items[0].type")).isEqualTo("CREATED");
        UUID ownerId = jdbc.queryForObject("select id from iam.app_user where email = ?", UUID.class, OWNER_EMAIL);
        assertThat((String) JsonPath.read(events, "$.items[0].actorId")).isEqualTo(ownerId.toString());
    }

    @Test
    void viewIsSeparatedFromManageAndAnonymousUsersAreRejected() throws Exception {
        String id = create(admin, "Fornecedor Beta", "PJ", cnpj());

        // GERENTE_FINANCEIRO só consulta.
        api.read(finance, "/api/purchasing/suppliers").andExpect(status().isOk());
        api.read(finance, "/api/purchasing/suppliers/" + id).andExpect(status().isOk());
        api.read(finance, "/api/purchasing/suppliers/" + id + "/contacts").andExpect(status().isOk());
        api.send(finance, post("/api/purchasing/suppliers"), body("Sem permissão", "PJ", cnpj(), null)).andExpect(status().isForbidden());
        api.send(finance, put("/api/purchasing/suppliers/" + id), body("Sem permissão", "PJ", null, 0L)).andExpect(status().isForbidden());
        api.send(finance, post("/api/purchasing/suppliers/" + id + "/inactivate"), "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        api.send(finance, post("/api/purchasing/suppliers/" + id + "/reactivate"), "{}").andExpect(status().isForbidden());
        api.send(finance, post("/api/purchasing/suppliers/" + id + "/contacts"), "{\"name\":\"Fulano\"}").andExpect(status().isForbidden());
        api.read(finance, "/api/purchasing/suppliers/" + id + "/events").andExpect(status().isForbidden());

        // Sem sessão: nada é lido nem gravado.
        mvc.perform(get("/api/purchasing/suppliers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/purchasing/suppliers").contentType("application/json").content(body("Anônimo", "PJ", cnpj(), null)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void naturalPersonDocumentIsMaskedForViewersAndInTheAuditTrail() throws Exception {
        String id = JsonPath.read(api.send(admin, post("/api/purchasing/suppliers"), body("João Autônomo", "PF", "529.982.247-25", null))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        api.read(admin, "/api/purchasing/suppliers/" + id).andExpect(jsonPath("$.document").value("52998224725"))
                .andExpect(jsonPath("$.documentMasked").value(false));
        api.read(finance, "/api/purchasing/suppliers/" + id).andExpect(jsonPath("$.document").value("*********25"))
                .andExpect(jsonPath("$.documentMasked").value(true));
        assertThat(listing(finance, "q", "Autônomo")).contains("*********25").doesNotContain("52998224725");

        // Alterar o CPF grava só a máscara na trilha.
        api.send(admin, put("/api/purchasing/suppliers/" + id), body("João Autônomo", "PF", "390.533.447-05", 0L)).andExpect(status().isOk());
        String events = read(admin, "/api/purchasing/suppliers/" + id + "/events");
        assertThat(events).contains("*********25").contains("*********05").doesNotContain("52998224725").doesNotContain("39053344705");
    }

    @Test
    void invalidDataIsRefusedWithStableCodes() throws Exception {
        api.send(owner, post("/api/purchasing/suppliers"), body("CNPJ errado", "PJ", "67757350000179", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_DOCUMENT_INVALID"));
        api.send(owner, post("/api/purchasing/suppliers"), body("CPF em PJ", "PJ", "52998224725", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_DOCUMENT_INVALID"));
        api.send(owner, post("/api/purchasing/suppliers"), "{\"personType\":\"PJ\",\"legalName\":\"  \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        api.send(owner, post("/api/purchasing/suppliers"), "{\"personType\":\"XX\",\"legalName\":\"Tipo\"}").andExpect(status().isBadRequest());
        api.send(owner, post("/api/purchasing/suppliers"), "{\"personType\":\"PJ\",\"legalName\":\"UF\",\"address\":{\"state\":\"ZZ\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_INVALID"));
        api.read(owner, "/api/purchasing/suppliers/" + UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
    }

    @Test
    void supplierWithoutDocumentIsAllowedAndSeveralMayCoexist() throws Exception {
        create(owner, "Sem documento 1", "PJ", null);
        create(owner, "Sem documento 2", "PJ", null);
        assertThat((Integer) JsonPath.read(listing(owner, "q", "Sem documento"), "$.totalItems")).isEqualTo(2);
    }

    /**
     * Regressão: `relatedParty` e `primary` são opcionais no corpo. Com campo primitivo no record de
     * entrada, a ausência virava "Cannot map `null` into type `boolean`" e todo cadastro respondia 400,
     * sem o formulário do frontend perceber (ele sempre envia o campo). Ausente significa `false`.
     */
    @Test
    void optionalBooleansMayBeOmittedAndDefaultToFalse() throws Exception {
        String id = JsonPath.read(api.send(owner, post("/api/purchasing/suppliers"),
                        "{\"personType\":\"PJ\",\"legalName\":\"Booleano Ausente Ltda\",\"document\":\"" + cnpj() + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.relatedParty").value(false))
                .andReturn().getResponse().getContentAsString(), "$.id");

        api.send(owner, post("/api/purchasing/suppliers/" + id + "/contacts"), "{\"name\":\"Contato sem primary\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.primary").value(false));

        assertThat(jdbc.queryForObject("select related_party from purchasing.supplier where id = ?::uuid", Boolean.class, id)).isFalse();
    }

    // ------------------------------------------------------------------ unicidade e versão

    @Test
    void duplicateDocumentIsRefusedWhateverTheMask() throws Exception {
        String document = cnpj();
        create(owner, "Fornecedor Original", "PJ", document);
        String masked = document.substring(0, 2) + "." + document.substring(2, 5) + "." + document.substring(5, 8) + "/" + document.substring(8, 12) + "-" + document.substring(12);
        api.send(owner, post("/api/purchasing/suppliers"), body("Fornecedor Duplicado", "PJ", masked, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_DOCUMENT_DUPLICATE"));
        assertThat(jdbc.queryForObject("select count(*) from purchasing.supplier where document = ?", Long.class, document)).isEqualTo(1);
    }

    @Test
    void twoSimultaneousRegistrationsOfTheSameDocumentCreateExactlyOne() throws Exception {
        String document = cnpj();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String name = "Concorrente " + i;
                Callable<Integer> call = () -> {
                    start.await();
                    return api.send(owner, post("/api/purchasing/suppliers"), body(name, "PJ", document, null)).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from purchasing.supplier where document = ?", Long.class, document)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from purchasing.supplier_event e join purchasing.supplier s on s.id = e.supplier_id where s.document = ?",
                Long.class, document)).isEqualTo(1);
    }

    @Test
    void staleVersionIsRefusedAndNothingIsOverwritten() throws Exception {
        String document = cnpj();
        String id = create(owner, "Fornecedor Gama", "PJ", document);

        api.send(owner, put("/api/purchasing/suppliers/" + id), body("Gama Editado", "PJ", document, 0L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.legalName").value("Gama Editado"));
        api.send(admin, put("/api/purchasing/suppliers/" + id), body("Edição Atrasada", "PJ", document, 0L))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_VERSION_CONFLICT"));
        api.send(owner, put("/api/purchasing/suppliers/" + id), body("Sem versão", "PJ", document, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUPPLIER_VERSION_REQUIRED"));

        api.read(owner, "/api/purchasing/suppliers/" + id).andExpect(jsonPath("$.legalName").value("Gama Editado")).andExpect(jsonPath("$.version").value(1));
        String events = read(owner, "/api/purchasing/suppliers/" + id + "/events");
        assertThat((Integer) JsonPath.read(events, "$.totalItems")).isEqualTo(2);
        assertThat((String) JsonPath.read(events, "$.items[0].type")).isEqualTo("UPDATED");
        assertThat((String) JsonPath.read(events, "$.items[0].changes.legalName.before")).isEqualTo("Fornecedor Gama");
    }

    // ------------------------------------------------------------------ inativação e histórico

    @Test
    void inactivationKeepsTheSupplierAndItsHistoryAndReactivationRestoresIt() throws Exception {
        String id = create(owner, "Fornecedor Delta", "PJ", cnpj());

        api.send(owner, post("/api/purchasing/suppliers/" + id + "/inactivate"), "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        api.send(owner, post("/api/purchasing/suppliers/" + id + "/inactivate"), "{\"reason\":\"Parou de atender a região\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.inactivationReason").value("Parou de atender a região"));
        api.send(owner, post("/api/purchasing/suppliers/" + id + "/inactivate"), "{\"reason\":\"de novo\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_ALREADY_INACTIVE"));

        assertThat(listing(owner, "status", "INACTIVE", "q", "Delta")).contains(id);
        assertThat(listing(owner, "status", "ACTIVE", "q", "Delta")).doesNotContain(id);

        api.send(owner, post("/api/purchasing/suppliers/" + id + "/reactivate"), "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.inactivationReason").doesNotExist());
        api.send(owner, post("/api/purchasing/suppliers/" + id + "/reactivate"), "{}").andExpect(status().isConflict());

        String events = read(owner, "/api/purchasing/suppliers/" + id + "/events");
        List<String> types = JsonPath.read(events, "$.items[*].type");
        assertThat(types).containsExactly("REACTIVATED", "INACTIVATED", "CREATED");
        assertThat((String) JsonPath.read(events, "$.items[1].reason")).isEqualTo("Parou de atender a região");
    }

    // ------------------------------------------------------------------ contatos

    @Test
    void contactsKeepASinglePrimaryAndRemovalOnlyDeactivates() throws Exception {
        String id = create(owner, "Fornecedor Épsilon", "PJ", cnpj());
        String base = "/api/purchasing/suppliers/" + id + "/contacts";

        String first = JsonPath.read(api.send(owner, post(base), "{\"name\":\"Vendedor\",\"role\":\"vendas\",\"phone\":\"34999990000\",\"primary\":true}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.primary").value(true)).andReturn().getResponse().getContentAsString(), "$.id");
        String second = JsonPath.read(api.send(owner, post(base), "{\"name\":\"Financeiro\",\"email\":\"fin@exemplo.test\",\"primary\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        List<String> primaries = JsonPath.read(read(owner, base), "$[?(@.primary == true)].id");
        assertThat(primaries).containsExactly(second);

        api.send(owner, put(base + "/" + first), "{\"name\":\"Vendedor Sênior\",\"primary\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Vendedor Sênior"));
        primaries = JsonPath.read(read(owner, base), "$[?(@.primary == true)].id");
        assertThat(primaries).containsExactly(first);

        api.send(owner, post(base), "{\"name\":\"Contato inválido\",\"email\":\"sem-arroba\"}").andExpect(status().isBadRequest());

        api.send(owner, delete(base + "/" + first), null).andExpect(status().isNoContent());
        api.send(owner, delete(base + "/" + first), null).andExpect(status().isNoContent());
        assertThat((List<?>) JsonPath.read(read(owner, base), "$")).hasSize(1);
        assertThat((List<?>) JsonPath.read(read(owner, base + "?includeInactive=true"), "$")).hasSize(2);
        api.send(owner, put(base + "/" + first), "{\"name\":\"Ressuscitado\"}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from purchasing.supplier_event where supplier_id = ?::uuid and event_type = 'CONTACT_REMOVED'",
                Long.class, id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ garantias do banco

    @Test
    void theDatabaseItselfRefusesToEraseOrRewriteHistory() throws Exception {
        String id = create(owner, "Fornecedor Zeta", "PJ", cnpj());
        String contact = JsonPath.read(api.send(owner, post("/api/purchasing/suppliers/" + id + "/contacts"), "{\"name\":\"Contato\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        assertThatThrownBy(() -> jdbc.update("delete from purchasing.supplier where id = ?::uuid", id)).hasMessageContaining("purchasing_record_is_append_only");
        assertThatThrownBy(() -> jdbc.update("delete from purchasing.supplier_contact where id = ?::uuid", contact)).hasMessageContaining("purchasing_record_is_append_only");
        assertThatThrownBy(() -> jdbc.update("update purchasing.supplier_event set reason = 'reescrito' where supplier_id = ?::uuid", id))
                .hasMessageContaining("purchasing_record_is_append_only");
        assertThatThrownBy(() -> jdbc.update("delete from purchasing.supplier_event where supplier_id = ?::uuid", id))
                .hasMessageContaining("purchasing_record_is_append_only");
    }

    @Test
    void theDatabaseRefusesInconsistentSuppliersEvenWithoutTheApplication() {
        String insert = "insert into purchasing.supplier (id, person_type, legal_name, document, status, inactivated_at, inactivated_by, inactivation_reason,"
                + " created_at, created_by, updated_at, updated_by) values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, now(), gen_random_uuid(), now(), gen_random_uuid())";
        // CNPJ em pessoa física e CPF em pessoa jurídica.
        assertThatThrownBy(() -> jdbc.update(insert, "PF", "Errado 1", "67757350000178", "ACTIVE", null, null, null)).hasMessageContaining("ck_supplier_document_person_type");
        assertThatThrownBy(() -> jdbc.update(insert, "PJ", "Errado 2", "52998224725", "ACTIVE", null, null, null)).hasMessageContaining("ck_supplier_document_person_type");
        // Formato inválido.
        assertThatThrownBy(() -> jdbc.update(insert, "PJ", "Errado 3", "12.345", "ACTIVE", null, null, null)).hasMessageContaining("ck_supplier_document_format");
        // Inativo sem motivo / ativo com marca de inativação.
        assertThatThrownBy(() -> jdbc.update(insert, "PJ", "Errado 4", null, "INACTIVE", null, null, null)).hasMessageContaining("ck_supplier_inactivation");
        assertThatThrownBy(() -> jdbc.update(insert, "PJ", "Errado 5", null, "ACTIVE", java.sql.Timestamp.from(java.time.Instant.now()), UUID.randomUUID(), "x"))
                .hasMessageContaining("ck_supplier_inactivation");
    }

    @Test
    void twoActivePrimaryContactsOrAnInactivePrimaryAreRejectedByTheDatabase() throws Exception {
        String id = create(owner, "Fornecedor Eta", "PJ", cnpj());
        String insert = "insert into purchasing.supplier_contact (id, supplier_id, name, primary_contact, active, created_at, created_by, updated_at)"
                + " values (gen_random_uuid(), ?::uuid, ?, ?, ?, now(), gen_random_uuid(), now())";
        jdbc.update(insert, id, "Principal", true, true);
        assertThatThrownBy(() -> jdbc.update(insert, id, "Outro principal", true, true)).hasMessageContaining("uq_supplier_contact_primary");
        assertThatThrownBy(() -> jdbc.update(insert, id, "Inativo principal", true, false)).hasMessageContaining("ck_supplier_contact_primary_active");
    }

    // ------------------------------------------------------------------ atomicidade

    @Test
    void aFailureAfterTheSupplierRowRollsBackTheWholeRegistration() throws Exception {
        String name = "FALHA-INJETADA-" + UUID.randomUUID();
        jdbc.execute("create function purchasing.test_fail_event() returns trigger language plpgsql as $$ begin"
                + " if exists (select 1 from purchasing.supplier s where s.id = new.supplier_id and s.legal_name like 'FALHA-INJETADA-%')"
                + " then raise exception 'falha injetada no teste'; end if; return new; end $$");
        jdbc.execute("create trigger tg_test_fail_event before insert on purchasing.supplier_event for each row execute function purchasing.test_fail_event()");
        try {
            int outcome;
            try {
                outcome = api.send(owner, post("/api/purchasing/suppliers"), body(name, "PJ", cnpj(), null)).andReturn().getResponse().getStatus();
            } catch (Exception failure) {
                outcome = 500; // a falha sobe como exceção quando nenhum handler a converte em resposta
            }
            assertThat(outcome).isEqualTo(500);
            assertThat(jdbc.queryForObject("select count(*) from purchasing.supplier where legal_name = ?", Long.class, name)).isZero();
        } finally {
            jdbc.execute("drop trigger tg_test_fail_event on purchasing.supplier_event");
            jdbc.execute("drop function purchasing.test_fail_event()");
        }
        // Depois de remover a falha, o mesmo cadastro funciona: nada ficou preso.
        create(owner, name, "PJ", cnpj());
    }

    // ------------------------------------------------------------------ consulta

    @Test
    void searchFiltersByTextDocumentAndTypeAndCapsThePageSize() throws Exception {
        String document = cnpj();
        create(owner, "Busca Sintética Ltda", "PJ", document);
        create(owner, "Busca Pessoa", "PF", cpf());

        assertThat((Integer) JsonPath.read(listing(owner, "q", "Busca Sint"), "$.totalItems")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(listing(owner, "q", document.substring(0, 8)), "$.totalItems")).isGreaterThanOrEqualTo(1);
        assertThat(listing(owner, "q", "Busca", "personType", "PF")).contains("Busca Pessoa").doesNotContain("Busca Sintética");
        assertThat((Integer) JsonPath.read(listing(owner, "size", "5000"), "$.size")).isEqualTo(100);
        assertThat((Integer) JsonPath.read(listing(owner, "q", "%"), "$.totalItems")).isZero();
    }
}
