package br.com.uberhidraulica.erp.purchasing.domain;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.BrazilianTaxId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fornecedor (AG-05 §§6-12): pessoa física ou jurídica, com condições comerciais padrão. Nunca é apagado; inativar
 * impede novos usos e não altera compras, cotações ou preços passados. Os dados são validados e normalizados na
 * escrita ({@link Draft#validated()}); a leitura do banco apenas reconstrói o registro.
 */
public record Supplier(UUID id, PersonType personType, String legalName, String tradeName, String document,
                       String stateRegistration, String phone, String email, Address address, CommercialTerms terms,
                       boolean relatedParty, String relatedPartyNote, Status status, Instant inactivatedAt, UUID inactivatedBy,
                       String inactivationReason, long version, Instant createdAt, UUID createdBy, Instant updatedAt,
                       UUID updatedBy) {

    public enum PersonType { PF, PJ }

    public enum Status { ACTIVE, INACTIVE }

    private static final Set<String> STATES = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG",
            "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Supplier {
        if (id == null || personType == null || status == null || createdAt == null || createdBy == null || updatedAt == null || updatedBy == null)
            throw new PurchasingException("SUPPLIER_INVALID", "Dados obrigatórios do fornecedor ausentes");
        if (address == null) address = Address.EMPTY;
        if (terms == null) terms = CommercialTerms.EMPTY;
    }

    public record Address(String zipCode, String street, String number, String complement, String district, String city, String state) {
        public static final Address EMPTY = new Address(null, null, null, null, null, null, null);

        Address validated() {
            String zip = digitsOrNull(zipCode);
            if (zip != null && zip.length() != 8) throw invalid("CEP deve ter 8 dígitos");
            String uf = text(state, "UF", 2);
            if (uf != null) {
                uf = uf.toUpperCase(java.util.Locale.ROOT);
                if (!STATES.contains(uf)) throw invalid("UF inválida: " + uf);
            }
            return new Address(zip, text(street, "Logradouro", 160), text(number, "Número", 20), text(complement, "Complemento", 80),
                    text(district, "Bairro", 80), text(city, "Cidade", 80), uf);
        }
    }

    /** Condições comerciais padrão; uma compra específica pode usar outras (AG-05 §11). */
    public record CommercialTerms(String paymentTerms, String preferredPaymentMethod, Integer usualDueDay, BigDecimal creditLimit,
                                  String notes) {
        public static final CommercialTerms EMPTY = new CommercialTerms(null, null, null, null, null);

        CommercialTerms validated() {
            if (usualDueDay != null && (usualDueDay < 1 || usualDueDay > 31)) throw invalid("Dia usual de vencimento deve estar entre 1 e 31");
            BigDecimal limit = creditLimit;
            if (limit != null) {
                if (limit.signum() < 0) throw invalid("Limite de crédito não pode ser negativo");
                if (limit.stripTrailingZeros().scale() > 2) throw invalid("Limite de crédito aceita no máximo duas casas decimais");
                limit = limit.setScale(2, RoundingMode.UNNECESSARY);
            }
            return new CommercialTerms(text(paymentTerms, "Condição de pagamento", 160), text(preferredPaymentMethod, "Forma preferencial", 80),
                    usualDueDay, limit, text(notes, "Observações comerciais", 1000));
        }
    }

    /** Dados informados pelo usuário em cadastro e edição. */
    public record Draft(PersonType personType, String legalName, String tradeName, String document, String stateRegistration,
                        String phone, String email, Address address, CommercialTerms terms, boolean relatedParty,
                        String relatedPartyNote) {

        public Draft validated() {
            if (personType == null) throw invalid("Tipo de pessoa é obrigatório");
            String name = text(legalName, personType == PersonType.PF ? "Nome" : "Razão social", 160);
            if (name == null) throw invalid((personType == PersonType.PF ? "Nome" : "Razão social") + " é obrigatório");
            String doc = BrazilianTaxId.normalize(document);
            if (doc != null && doc.isEmpty()) doc = null;
            if (doc != null) {
                boolean ok = personType == PersonType.PF ? BrazilianTaxId.isValidCpf(doc) : BrazilianTaxId.isValidCnpj(doc);
                if (!ok) throw new PurchasingException("SUPPLIER_DOCUMENT_INVALID",
                        (personType == PersonType.PF ? "CPF" : "CNPJ") + " inválido para o tipo de pessoa informado");
            }
            String mail = text(email, "E-mail", 254);
            if (mail != null && !EMAIL.matcher(mail).matches()) throw invalid("E-mail inválido");
            String note = text(relatedPartyNote, "Observação da empresa relacionada", 300);
            if (note != null && !relatedParty) throw invalid("A observação de empresa relacionada exige marcar o fornecedor como relacionado");
            return new Draft(personType, name, text(tradeName, "Nome fantasia", 160), doc, text(stateRegistration, "Inscrição estadual", 30),
                    text(phone, "Telefone", 24), mail, (address == null ? Address.EMPTY : address).validated(),
                    (terms == null ? CommercialTerms.EMPTY : terms).validated(), relatedParty, note);
        }
    }

    public static Supplier register(Draft draft, UUID actor, Instant now) {
        Draft d = draft.validated();
        return new Supplier(UUID.randomUUID(), d.personType(), d.legalName(), d.tradeName(), d.document(), d.stateRegistration(), d.phone(),
                d.email(), d.address(), d.terms(), d.relatedParty(), d.relatedPartyNote(), Status.ACTIVE, null, null, null, 0, now, actor, now, actor);
    }

    /** Aplica a edição mantendo identidade, situação e versão (a versão só avança na gravação). */
    public Supplier revise(Draft draft, UUID actor, Instant now) {
        Draft d = draft.validated();
        return new Supplier(id, d.personType(), d.legalName(), d.tradeName(), d.document(), d.stateRegistration(), d.phone(), d.email(),
                d.address(), d.terms(), d.relatedParty(), d.relatedPartyNote(), status, inactivatedAt, inactivatedBy, inactivationReason,
                version, createdAt, createdBy, now, actor);
    }

    public Supplier inactivate(String reason, UUID actor, Instant now) {
        if (status == Status.INACTIVE) throw new PurchasingException("SUPPLIER_ALREADY_INACTIVE", "Fornecedor já está inativo");
        String why = text(reason, "Motivo da inativação", 500);
        if (why == null) throw invalid("Motivo da inativação é obrigatório");
        return new Supplier(id, personType, legalName, tradeName, document, stateRegistration, phone, email, address, terms, relatedParty,
                relatedPartyNote, Status.INACTIVE, now, actor, why, version, createdAt, createdBy, now, actor);
    }

    public Supplier reactivate(UUID actor, Instant now) {
        if (status == Status.ACTIVE) throw new PurchasingException("SUPPLIER_ALREADY_ACTIVE", "Fornecedor já está ativo");
        return new Supplier(id, personType, legalName, tradeName, document, stateRegistration, phone, email, address, terms, relatedParty,
                relatedPartyNote, Status.ACTIVE, null, null, null, version, createdAt, createdBy, now, actor);
    }

    public boolean active() { return status == Status.ACTIVE; }

    /** CPF é dado pessoal: quem só consulta vê apenas os dois últimos dígitos. CNPJ é dado de empresa e segue completo. */
    public String documentFor(boolean mayViewPersonalData) {
        if (document == null || personType == PersonType.PJ || mayViewPersonalData) return document;
        return maskPersonalDocument(document);
    }

    public static String maskPersonalDocument(String document) {
        if (document == null) return null;
        return "*".repeat(Math.max(0, document.length() - 2)) + document.substring(document.length() - 2);
    }

    /** Campos que mudaram entre esta versão e a anterior, para a trilha de auditoria (CPF sempre mascarado). */
    public Map<String, SupplierEvent.Change> changesFrom(Supplier before) {
        Map<String, SupplierEvent.Change> changes = new LinkedHashMap<>();
        diff(changes, "personType", before.personType, personType);
        diff(changes, "legalName", before.legalName, legalName);
        diff(changes, "tradeName", before.tradeName, tradeName);
        diff(changes, "document", before.personType == PersonType.PF ? maskPersonalDocument(before.document) : before.document,
                personType == PersonType.PF ? maskPersonalDocument(document) : document);
        diff(changes, "stateRegistration", before.stateRegistration, stateRegistration);
        diff(changes, "phone", before.phone, phone);
        diff(changes, "email", before.email, email);
        diff(changes, "address.zipCode", before.address.zipCode(), address.zipCode());
        diff(changes, "address.street", before.address.street(), address.street());
        diff(changes, "address.number", before.address.number(), address.number());
        diff(changes, "address.complement", before.address.complement(), address.complement());
        diff(changes, "address.district", before.address.district(), address.district());
        diff(changes, "address.city", before.address.city(), address.city());
        diff(changes, "address.state", before.address.state(), address.state());
        diff(changes, "terms.paymentTerms", before.terms.paymentTerms(), terms.paymentTerms());
        diff(changes, "terms.preferredPaymentMethod", before.terms.preferredPaymentMethod(), terms.preferredPaymentMethod());
        diff(changes, "terms.usualDueDay", before.terms.usualDueDay(), terms.usualDueDay());
        diff(changes, "terms.creditLimit", plain(before.terms.creditLimit()), plain(terms.creditLimit()));
        diff(changes, "terms.notes", before.terms.notes(), terms.notes());
        diff(changes, "relatedParty", before.relatedParty, relatedParty);
        diff(changes, "relatedPartyNote", before.relatedPartyNote, relatedPartyNote);
        return changes;
    }

    private static void diff(Map<String, SupplierEvent.Change> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) changes.put(field, new SupplierEvent.Change(before == null ? null : before.toString(),
                after == null ? null : after.toString()));
    }

    private static String plain(BigDecimal value) { return value == null ? null : value.toPlainString(); }

    // ------------------------------------------------------------------ normalização compartilhada

    static PurchasingException invalid(String message) { return new PurchasingException("SUPPLIER_INVALID", message); }

    /** Texto aparado, nulo quando vazio, recusado quando passa do limite. */
    static String text(String value, String label, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > max) throw invalid(label + " excede " + max + " caracteres");
        return trimmed;
    }

    static String digitsOrNull(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }
}
