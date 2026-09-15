package br.com.uberhidraulica.erp.quote.infrastructure.persistence;

import br.com.uberhidraulica.erp.quote.domain.DecisionSubmission;
import br.com.uberhidraulica.erp.quote.domain.DecisionType;
import br.com.uberhidraulica.erp.quote.domain.DocumentType;
import br.com.uberhidraulica.erp.quote.domain.PublicQuoteAccess;
import br.com.uberhidraulica.erp.quote.port.PublicQuoteRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência do acesso público e das decisões.
 *
 * <p>Usa JDBC direto em vez de JPA porque {@code ip_address} é {@code INET} e {@code token_digest} é
 * {@code BYTEA}: mapear esses tipos por entidade exigiria conversores que só existiriam para
 * contornar o mapeamento, sem ganho nenhum sobre três tabelas de escrita simples e imutável.</p>
 */
@Component
public class JdbcPublicQuoteRepositoryAdapter implements PublicQuoteRepositoryPort {
    private final JdbcTemplate jdbc;

    JdbcPublicQuoteRepositoryAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<PublicQuoteAccess> ACCESS = (rs, row) -> new PublicQuoteAccess(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("quote_id")),
            UUID.fromString(rs.getString("quote_revision_id")),
            rs.getBytes("token_digest"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("valid_until").toInstant(),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null),
            UUID.fromString(rs.getString("created_by")));

    @Override
    public PublicQuoteAccess create(PublicQuoteAccess access) {
        jdbc.update("insert into workshop.public_quote_access"
                        + " (id, quote_id, quote_revision_id, token_digest, created_at, valid_until,"
                        + " revoked_at, created_by) values (?, ?, ?, ?, ?, ?, null, ?)",
                access.id(), access.quoteId(), access.quoteRevisionId(), access.tokenDigest(),
                Timestamp.from(access.createdAt()), Timestamp.from(access.validUntil()), access.createdBy());
        return access;
    }

    @Override
    public Optional<PublicQuoteAccess> findByTokenDigest(byte[] tokenDigest) {
        return jdbc.query("select * from workshop.public_quote_access where token_digest = ?",
                ACCESS, (Object) tokenDigest).stream().findFirst();
    }

    @Override
    public List<PublicQuoteAccess> findByQuoteId(UUID quoteId) {
        return jdbc.query("select * from workshop.public_quote_access where quote_id = ?"
                + " order by created_at desc, id", ACCESS, quoteId);
    }

    @Override
    public boolean revoke(UUID accessId, Instant revokedAt) {
        return jdbc.update("update workshop.public_quote_access set revoked_at = ?"
                + " where id = ? and revoked_at is null", Timestamp.from(revokedAt), accessId) == 1;
    }

    @Override
    public Map<UUID, DecisionType> decisionsByQuote(UUID quoteId) {
        Map<UUID, DecisionType> decisions = new HashMap<>();
        jdbc.query("select quote_item_revision_id, decision_type from workshop.quote_decision"
                + " where quote_id = ?", rs -> {
            decisions.put(UUID.fromString(rs.getString("quote_item_revision_id")),
                    DecisionType.valueOf(rs.getString("decision_type")));
        }, quoteId);
        return Map.copyOf(decisions);
    }

    /** Linha crua da submissão; as decisões são lidas depois, porque o domínio exige ao menos uma. */
    private record SubmissionRow(UUID id, UUID accessId, UUID revisionId, UUID quoteId, String requestId,
                                 byte[] digest, String name, DocumentType documentType, String documentNumber,
                                 boolean acceptance, Instant occurredAt, String ip, String userAgent) {}

    @Override
    public Optional<DecisionSubmission> findSubmission(UUID publicQuoteAccessId, String requestId) {
        List<SubmissionRow> rows = jdbc.query(
                "select * from workshop.quote_decision_submission"
                        + " where public_quote_access_id = ? and request_id = ?",
                (rs, row) -> new SubmissionRow(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("public_quote_access_id")),
                        UUID.fromString(rs.getString("quote_revision_id")),
                        UUID.fromString(rs.getString("quote_id")),
                        rs.getString("request_id"),
                        rs.getBytes("request_payload_digest"),
                        rs.getString("customer_name"),
                        DocumentType.valueOf(rs.getString("document_type")),
                        rs.getString("document_number"),
                        rs.getBoolean("explicit_acceptance"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("ip_address"),
                        rs.getString("user_agent")),
                publicQuoteAccessId, requestId);
        if (rows.isEmpty()) return Optional.empty();
        SubmissionRow row = rows.get(0);
        return Optional.of(new DecisionSubmission(row.id(), row.accessId(), row.revisionId(), row.quoteId(),
                row.requestId(), row.digest(), row.name(), row.documentType(), row.documentNumber(),
                row.acceptance(), row.occurredAt(), row.ip(), row.userAgent(), decisionsOf(row.id())));
    }

    private List<DecisionSubmission.Decision> decisionsOf(UUID submissionId) {
        return jdbc.query("select id, quote_item_revision_id, decision_type, occurred_at"
                        + " from workshop.quote_decision where submission_id = ? order by occurred_at, id",
                (rs, row) -> new DecisionSubmission.Decision(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("quote_item_revision_id")),
                        DecisionType.valueOf(rs.getString("decision_type")),
                        rs.getTimestamp("occurred_at").toInstant()),
                submissionId);
    }

    @Override
    public void save(DecisionSubmission submission) {
        jdbc.update("insert into workshop.quote_decision_submission"
                        + " (id, public_quote_access_id, quote_revision_id, quote_id, request_id,"
                        + " request_payload_digest, customer_name, document_type, document_number,"
                        + " explicit_acceptance, occurred_at, ip_address, user_agent, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as inet), ?, ?)",
                submission.id(), submission.publicQuoteAccessId(), submission.quoteRevisionId(),
                submission.quoteId(), submission.requestId(), submission.payloadDigest(),
                submission.customerName(), submission.documentType().name(), submission.documentNumber(),
                submission.explicitAcceptance(), Timestamp.from(submission.occurredAt()),
                submission.ipAddress(), submission.userAgent(), Timestamp.from(submission.occurredAt()));

        for (DecisionSubmission.Decision decision : submission.decisions())
            jdbc.update("insert into workshop.quote_decision"
                            + " (id, submission_id, quote_revision_id, quote_item_revision_id, quote_id,"
                            + " decision_type, occurred_at) values (?, ?, ?, ?, ?, ?, ?)",
                    decision.id(), submission.id(), submission.quoteRevisionId(),
                    decision.quoteItemRevisionId(), submission.quoteId(), decision.decisionType().name(),
                    Timestamp.from(decision.occurredAt()));
    }
}
