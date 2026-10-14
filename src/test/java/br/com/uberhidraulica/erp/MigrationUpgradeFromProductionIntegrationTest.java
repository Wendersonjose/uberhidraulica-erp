package br.com.uberhidraulica.erp;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Promoção de V22–V25 sobre um banco que já está na V21, como o Supabase de produção (TASK-0021).
 *
 * <p>A suíte normal reconstrói o banco do ZERO a cada classe e por isso não responde à pergunta que importa antes de
 * promover: <b>as migrations novas aplicam sobre dados que já existem, sem tocar neles, sem sair do próprio território e sem
 * quebrar a versão antiga do aplicativo que continua rodando?</b> Este teste reproduz as condições de produção:</p>
 * <ul>
 *   <li>histórico do Flyway em {@code erp_meta} e {@code create-schemas}, como em {@code application-supabase.yml};</li>
 *   <li>uma tabela de outro módulo em {@code public} (o projeto Supabase também guarda o módulo de salários);</li>
 *   <li>dados reais de estoque, produto e contas a pagar na V21 antes de aplicar a V22.</li>
 * </ul>
 *
 * <p>Nada aqui fala com o Supabase: é um PostgreSQL 18 descartável.</p>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationUpgradeFromProductionIntegrationTest {
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @TempDir
    Path oldAppMigrations;

    private JdbcTemplate jdbc;
    private String beforeBusinessData;
    private Set<String> relationsAtV21;
    private long permissionGrantsAtV21;

    private Flyway flyway(String locations) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations)
                // application-supabase.yml: histórico isolado em erp_meta, schemas criados pela própria migration.
                .defaultSchema("erp_meta")
                .createSchemas(true)
                .load();
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        }
        return jdbc;
    }

    /** Impressão digital das colunas que já existiam: se alguma linha mudar, o hash muda. */
    private String fingerprint(String table, String columns) {
        return jdbc().queryForObject("select md5(coalesce(string_agg(row(" + columns + ")::text, '|' order by id), '')) from " + table,
                String.class);
    }

    private String businessData() {
        return String.join("#",
                fingerprint("productcatalog.product", "id, description, internal_code, category, item_type, unit, reference_cost, sale_price, minimum_stock, active"),
                jdbc().queryForObject("select md5(coalesce(string_agg(row(product_id, quantity, average_cost)::text, '|' order by product_id), '')) from inventory.stock_balance", String.class),
                fingerprint("inventory.stock_movement", "id, product_id, movement_type, direction, quantity, unit_cost, balance_after, average_cost_after, reason, source_type, work_order_id, work_order_item_id, reverses_movement_id"),
                fingerprint("finance.expense_category", "id, name, active"),
                fingerprint("finance.payable", "id, description, supplier, category_id, amount, due_date, idempotency_key"),
                fingerprint("public.outro_modulo_salario", "id, valor"));
    }

    private Set<String> relations() {
        return Set.copyOf(jdbc().queryForList("""
                select n.nspname || '.' || c.relname || ':' || c.relkind::text
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast') and n.nspname not like 'pg_temp%'
                """, String.class));
    }

    private void seedProductionLikeData() {
        UUID oil = UUID.randomUUID(), gallon = UUID.randomUUID(), retired = UUID.randomUUID();
        jdbc().update("""
                insert into productcatalog.product (id, description, internal_code, category, item_type, unit, reference_cost, sale_price, minimum_stock, active, created_at, updated_at)
                values (?, 'Filtro de óleo', 'FO-001', 'Filtros', 'PART', 'UNIDADE', 10.00, 25.00, 5, true, now(), now()),
                       (?, 'Óleo 5W30 galão', 'OL-5W30', null, 'SUPPLY', 'GALAO_5L', 80.00, 140.00, 2, true, now(), now()),
                       (?, 'Peça descontinuada', null, 'Antigas', 'PART', 'UNIDADE', null, null, null, false, now(), now())
                """, oil, gallon, retired);
        jdbc().update("""
                insert into inventory.stock_balance (product_id, quantity, average_cost, updated_at)
                values (?, 19, 10.0000, now()), (?, 3, 80.0000, now()), (?, 0, null, now())
                """, oil, gallon, retired);
        UUID entry = UUID.randomUUID(), exit = UUID.randomUUID();
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, average_cost_after, reason, source_type, occurred_at)
                values (?, ?, 'ENTRY', 'IN', 20, 10.0000, 20, 10.0000, 'Compra balcão', 'MANUAL', now() - interval '5 day'),
                       (?, ?, 'EXIT', 'OUT', 5, null, 15, 10.0000, null, 'MANUAL', now() - interval '4 day')
                """, entry, oil, exit, oil);
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, average_cost_after, reason, source_type, work_order_id, work_order_item_id, occurred_at)
                values (?, ?, 'WORK_ORDER_OUT', 'OUT', 2, null, 13, 10.0000, null, 'WORK_ORDER', ?, ?, now() - interval '3 day')
                """, UUID.randomUUID(), oil, UUID.randomUUID(), UUID.randomUUID());
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, average_cost_after, reason, source_type, occurred_at)
                values (?, ?, 'ADJUSTMENT_IN', 'IN', 1, 10.0000, 14, 10.0000, 'Contagem', 'MANUAL', now() - interval '2 day')
                """, UUID.randomUUID(), oil);
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, average_cost_after, reason, source_type, reverses_movement_id, occurred_at)
                values (?, ?, 'REVERSAL', 'IN', 5, null, 19, 10.0000, 'Saída lançada errada', 'MANUAL', ?, now() - interval '1 day')
                """, UUID.randomUUID(), oil, exit);
        UUID category = UUID.randomUUID();
        jdbc().update("insert into finance.expense_category (id, name, active, created_at, updated_at) values (?, 'Peças', true, now(), now())", category);
        jdbc().update("""
                insert into finance.payable (id, description, supplier, category_id, amount, due_date, created_at, created_by, idempotency_key)
                values (?, 'Boleto fornecedor antigo', 'Distribuidora Antiga', ?, 350.00, current_date + 10, now(), ?, 'prod-1'),
                       (?, 'Aluguel', null, ?, 1800.00, current_date + 20, now(), ?, 'prod-2')
                """, UUID.randomUUID(), category, UUID.randomUUID(), UUID.randomUUID(), category, UUID.randomUUID());
    }

    @Test
    @Order(1)
    void productionStateIsReproducedUpToV21WithDataAndAnotherModuleInPublic() {
        // Outro módulo no mesmo projeto Supabase: o ERP não pode tocá-lo.
        jdbc().execute("create table public.outro_modulo_salario (id uuid primary key, valor numeric(12,2) not null)");
        jdbc().update("insert into public.outro_modulo_salario values (?, 3200.50), (?, 4100.00)", UUID.randomUUID(), UUID.randomUUID());

        MigrateResult upToV21 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").defaultSchema("erp_meta").createSchemas(true).target("21").load().migrate();

        assertThat(upToV21.success).isTrue();
        assertThat(jdbc().queryForObject("select max(version::int) from erp_meta.flyway_schema_history where success", Integer.class)).isEqualTo(21);
        // Em produção o histórico fica em erp_meta, e não em public.
        assertThat(jdbc().queryForObject("select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'flyway_schema_history'", Long.class)).isZero();
        assertThat(jdbc().queryForObject("select count(*) from information_schema.schemata where schema_name = 'purchasing'", Long.class)).isZero();

        seedProductionLikeData();
        beforeBusinessData = businessData();
        relationsAtV21 = relations();
        permissionGrantsAtV21 = jdbc().queryForObject("select count(*) from iam.profile_permission", Long.class);
    }

    @Test
    @Order(2)
    void v22ToV25ApplyOverExistingDataWithoutTouchingIt() {
        beforeBusinessData = businessData();
        relationsAtV21 = relations();
        permissionGrantsAtV21 = jdbc().queryForObject("select count(*) from iam.profile_permission", Long.class);

        MigrateResult promoted = flyway("classpath:db/migration").migrate();

        assertThat(promoted.success).isTrue();
        assertThat(promoted.migrationsExecuted).as("só V22, V23, V24 e V25 estavam pendentes").isEqualTo(4);
        assertThat(promoted.targetSchemaVersion).isEqualTo("25");
        // Uma linha por migration; o Flyway pode acrescentar uma linha sem versão quando ele mesmo cria o schema erp_meta.
        assertThat(jdbc().queryForObject("select count(*) from erp_meta.flyway_schema_history where success and version is not null", Long.class))
                .isEqualTo(Long.valueOf(migrationFiles().size()));
        assertThat(jdbc().queryForObject("select count(*) from erp_meta.flyway_schema_history where not success", Long.class)).isZero();

        // Nenhuma linha de negócio que já existia mudou (produto, estoque, movimentos, financeiro e o outro módulo).
        assertThat(businessData()).as("dados de negócio e do outro módulo intactos").isEqualTo(beforeBusinessData);
    }

    @Test
    @Order(3)
    void theMigrationsStayInsideTheirOwnTerritory() {
        Set<String> created = relations().stream().filter(r -> !relationsAtV21.contains(r)).collect(Collectors.toSet());
        Set<String> outsidePurchasing = created.stream().filter(r -> !r.startsWith("purchasing.")).collect(Collectors.toSet());

        // Tudo o que é novo mora em `purchasing`, exceto 3 índices em tabelas de outros módulos (GTIN do produto e a
        // chave de idempotência da entrada de estoque por compra). Nada em `public` nem em `erp_meta`.
        assertThat(outsidePurchasing).containsExactlyInAnyOrder(
                "productcatalog.uq_product_gtin:i", "inventory.uq_stock_movement_purchase_entry:i",
                "inventory.ix_stock_movement_purchase_receipt:i");
        assertThat(created.stream().filter(r -> r.startsWith("purchasing.")).count()).isGreaterThan(20);
        assertThat(created.stream().noneMatch(r -> r.startsWith("public."))).isTrue();
        assertThat(jdbc().queryForObject("select count(*) from public.outro_modulo_salario", Long.class)).isEqualTo(2L);
    }

    @Test
    @Order(4)
    void newColumnsAreNullableAndPermissionsOnlyAreAdded() {
        assertThat(jdbc().queryForObject("select count(*) from productcatalog.product where gtin is not null or ncm is not null", Long.class)).isZero();
        assertThat(jdbc().queryForObject("select count(*) from inventory.stock_movement where purchase_receipt_item_id is not null", Long.class)).isZero();
        assertThat(jdbc().queryForObject("select count(*) from productcatalog.product", Long.class)).isEqualTo(3L);

        List<String> codes = jdbc().queryForList("select code from iam.permission where code like 'PURCHASE\\_%' order by code", String.class);
        assertThat(codes).containsExactlyInAnyOrder("PURCHASE_APPROVE", "PURCHASE_APPROVE_OWN", "PURCHASE_CANCEL", "PURCHASE_DIVERGENCE_ACCEPT",
                "PURCHASE_IMPORT_XML", "PURCHASE_ORDER_MANAGE", "PURCHASE_RECEIVE", "PURCHASE_SUPPLIER_MANAGE", "PURCHASE_VIEW");
        // Só acrescenta concessões: nenhuma das concessões que já existiam foi removida.
        assertThat(jdbc().queryForObject("select count(*) from iam.profile_permission", Long.class)).isGreaterThan(permissionGrantsAtV21);
        assertThat(jdbc().queryForObject("""
                select count(*) from iam.profile_permission pp join iam.profile p on p.id = pp.profile_id
                join iam.permission perm on perm.id = pp.permission_id
                where p.code = 'GERENTE_FINANCEIRO' and perm.code like 'PURCHASE\\_%'""", Long.class)).isEqualTo(1L);
    }

    @Test
    @Order(5)
    void theOldApplicationVersionKeepsWorkingOnTheUpgradedDatabase() throws IOException {
        // O rollback do APLICATIVO (voltar o Render para release/supabase-cloud) não desfaz as migrations: a versão
        // antiga volta a falar com um banco já na V25. Duas coisas precisam valer.
        for (Path file : migrationFiles()) {
            Matcher matcher = VERSION.matcher(file.getFileName().toString());
            if (matcher.matches() && Integer.parseInt(matcher.group(1)) <= 21) {
                Files.copy(file, oldAppMigrations.resolve(file.getFileName()));
            }
        }
        // 1. O Flyway da versão antiga (só conhece V1–V21) valida e sobe sem reclamar das migrations "do futuro".
        MigrateResult oldApp = flyway("filesystem:" + oldAppMigrations).migrate();
        assertThat(oldApp.success).isTrue();
        assertThat(oldApp.migrationsExecuted).isZero();
        flyway("filesystem:" + oldAppMigrations).validate();

        // 2. As escritas que a versão antiga faz continuam válidas com as restrições novas.
        UUID product = UUID.randomUUID();
        jdbc().update("""
                insert into productcatalog.product (id, description, internal_code, category, item_type, unit, active, created_at, updated_at)
                values (?, 'Cadastrado pela versão antiga', 'VELHO-1', null, 'PART', 'UNIDADE', true, now(), now())""", product);
        jdbc().update("insert into inventory.stock_balance (product_id, quantity, average_cost, updated_at) values (?, 4, 7.5000, now())", product);
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, average_cost_after, reason, source_type, occurred_at)
                values (?, ?, 'ENTRY', 'IN', 4, 7.5000, 4, 7.5000, 'Entrada manual', 'MANUAL', now())""", UUID.randomUUID(), product);
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, average_cost_after, reason, source_type, work_order_id, work_order_item_id, occurred_at)
                values (?, ?, 'WORK_ORDER_OUT', 'OUT', 1, null, 3, 7.5000, null, 'WORK_ORDER', ?, ?, now())""",
                UUID.randomUUID(), product, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @Order(6)
    void theNewConstraintsRefuseWhatTheyShouldAndAReRunChangesNothing() {
        UUID product = jdbc().queryForObject("select id from productcatalog.product where internal_code = 'FO-001'", UUID.class);
        // Movimento de compra sem o item recebido (ou o contrário) é recusado: origem e referência andam juntas.
        assertThatThrownBy(() -> jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, source_type, occurred_at)
                values (?, ?, 'ENTRY', 'IN', 1, 1.0000, 20, 'PURCHASE', now())""", UUID.randomUUID(), product))
                .isInstanceOf(DataAccessException.class);
        // GTIN só aceita 8 ou 12 a 14 dígitos: a restrição de formato do produto recusa.
        assertThatThrownBy(() -> jdbc().update("update productcatalog.product set gtin = 'ABC' where id = ?", product))
                .as("GTIN com formato inválido").isInstanceOf(DataAccessException.class);

        long historyBefore = jdbc().queryForObject("select count(*) from erp_meta.flyway_schema_history", Long.class);
        MigrateResult again = flyway("classpath:db/migration").migrate();
        assertThat(again.migrationsExecuted).isZero();
        assertThat(jdbc().queryForObject("select count(*) from erp_meta.flyway_schema_history", Long.class)).isEqualTo(historyBefore);
        flyway("classpath:db/migration").validate();
    }

    @Test
    @Order(7)
    void theDeliveredRollbackScriptRefusesWhenPurchasesTouchedStockAndOtherwiseRestoresV21ThenReApplies() throws IOException {
        String rollback = Files.readString(Path.of("scripts/ops/rollback-v22-v25.sql"));
        UUID product = jdbc().queryForObject("select id from productcatalog.product where internal_code = 'FO-001'", UUID.class);

        // Trava: se uma compra já deu entrada em estoque, o rollback recusa e NADA muda (a transação inteira volta).
        UUID purchaseMovement = UUID.randomUUID();
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, source_type, purchase_receipt_item_id, occurred_at)
                values (?, ?, 'ENTRY', 'IN', 1, 1.0000, 20, 'PURCHASE', ?, now())""", purchaseMovement, product, UUID.randomUUID());
        assertThatThrownBy(() -> jdbc().execute(rollback)).hasMessageContaining("Rollback recusado");
        assertThat(jdbc().queryForObject("select count(*) from information_schema.schemata where schema_name = 'purchasing'", Long.class)).isEqualTo(1L);
        assertThat(jdbc().queryForObject("select max(version::int) from erp_meta.flyway_schema_history where success", Integer.class)).isEqualTo(25);
        jdbc().update("delete from inventory.stock_movement where id = ?", purchaseMovement);

        // Sem uso real de Compras, o script devolve o banco ao formato da V21 sem tocar nos dados de negócio.
        String businessBefore = businessData();
        jdbc().execute(rollback);

        assertThat(businessData()).as("dados de negócio intactos pelo rollback").isEqualTo(businessBefore);
        assertThat(relations()).as("mesmas tabelas, índices e views da V21").isEqualTo(relationsAtV21);
        assertThat(jdbc().queryForObject("select max(version::int) from erp_meta.flyway_schema_history where success", Integer.class)).isEqualTo(21);
        assertThat(jdbc().queryForObject("select count(*) from iam.permission where code like 'PURCHASE\\_%'", Long.class)).isZero();
        assertThat(jdbc().queryForObject("select count(*) from iam.profile_permission", Long.class)).isEqualTo(permissionGrantsAtV21);
        assertThat(jdbc().queryForObject("select count(*) from information_schema.columns where table_schema = 'productcatalog' and table_name = 'product' and column_name in ('gtin', 'ncm')", Long.class)).isZero();

        // A versão antiga continua gravando normalmente, e promover de novo funciona (o rollback não deixa resíduo).
        jdbc().update("""
                insert into inventory.stock_movement (id, product_id, movement_type, direction, quantity, unit_cost, balance_after, source_type, occurred_at)
                values (?, ?, 'ENTRY', 'IN', 1, 1.0000, 20, 'MANUAL', now())""", UUID.randomUUID(), product);
        MigrateResult again = flyway("classpath:db/migration").migrate();
        assertThat(again.success).isTrue();
        assertThat(again.migrationsExecuted).isEqualTo(4);
        assertThat(jdbc().queryForObject("select max(version::int) from erp_meta.flyway_schema_history where success", Integer.class)).isEqualTo(25);
    }

    private static List<Path> migrationFiles() {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(file -> VERSION.matcher(file.getFileName().toString()).matches()).sorted().toList();
        } catch (IOException failure) {
            throw new IllegalStateException("Não foi possível listar as migrations em " + MIGRATIONS.toAbsolutePath(), failure);
        }
    }
}
