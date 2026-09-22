package br.com.uberhidraulica.erp.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Base comercial aprovada gravada direto no banco, para testes cujo assunto não é o orçamento.
 *
 * <p>Desde a DR-0015 (F-02), finalizar uma OS exige orçamento com item aprovado. Os testes de Estoque
 * finalizam OS para exercitar a baixa; esta fixture dá a eles a aprovação mínima, pelo mesmo caminho de
 * dados que uma decisão interna real deixa: apresentação, submissão {@code INTERNAL} e decisão {@code APPROVE}.
 * O fluxo comercial completo pela API é coberto pelos testes do Orçamento e do Financeiro.</p>
 */
public final class CommercialFixtures {
    private CommercialFixtures() {}

    /** Cria um orçamento apresentado com um único item aprovado e devolve o id do orçamento. */
    public static UUID approvedQuote(JdbcTemplate jdbc, UUID workOrderId, String description, BigDecimal price) {
        UUID author = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID itemRevision = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID submission = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Timestamp at = Timestamp.from(now);
        jdbc.update("insert into workshop.quote (id, work_order_id, version, created_at, created_by) values (?, ?, 0, ?, ?)",
                quote, workOrderId, at, author);
        jdbc.update("insert into workshop.quote_item (id, quote_id, created_at, created_by) values (?, ?, ?, ?)", item, quote, at, author);
        jdbc.update("insert into workshop.quote_item_revision (id, quote_id, quote_item_id, revision_sequence, description, quantity,"
                + " unit_price, total_price, discount_amount, created_at, created_by) values (?, ?, ?, 1, ?, 1, ?, ?, 0, ?, ?)",
                itemRevision, quote, item, description, price, price, at, author);
        jdbc.update("insert into workshop.quote_revision (id, quote_id, revision_number, status, presented_at, valid_until, created_at,"
                + " created_by) values (?, ?, 1, 'PRESENTED', ?, ?, ?, ?)", revision, quote, at, Timestamp.from(now.plus(7, ChronoUnit.DAYS)), at, author);
        jdbc.update("insert into workshop.quote_revision_item (quote_revision_id, quote_item_revision_id, quote_id, display_order)"
                + " values (?, ?, ?, 1)", revision, itemRevision, quote);
        jdbc.update("insert into workshop.quote_decision_submission (id, quote_revision_id, quote_id, request_id, explicit_acceptance,"
                + " occurred_at, created_at, channel, contact_channel, recorded_by) values (?, ?, ?, ?, true, ?, ?, 'INTERNAL', 'PRESENCIAL', ?)",
                submission, revision, quote, UUID.randomUUID().toString(), at, at, author);
        jdbc.update("insert into workshop.quote_decision (id, submission_id, quote_revision_id, quote_item_revision_id, quote_id,"
                + " decision_type, occurred_at) values (?, ?, ?, ?, ?, 'APPROVE', ?)", UUID.randomUUID(), submission, revision, itemRevision, quote, at);
        return quote;
    }

    /** Ordem de remoção que respeita as FKs entre Financeiro, Orçamento e OS. */
    public static final String[] FINANCE_AND_QUOTE_TABLES = {
            "finance.receipt_reversal", "finance.receipt", "finance.receivable_adjustment", "finance.receivable_due_date_change",
            "finance.receivable_line", "finance.receivable",
            "finance.payable_payment_reversal", "finance.payable_payment", "finance.payable",
            "workshop.quote_decision", "workshop.quote_decision_submission", "workshop.public_quote_access",
            "workshop.quote_revision_item", "workshop.quote_item_revision", "workshop.quote_item", "workshop.quote_revision", "workshop.quote"};

    public static void clean(JdbcTemplate jdbc) {
        for (String table : FINANCE_AND_QUOTE_TABLES) jdbc.update("delete from " + table);
    }
}
