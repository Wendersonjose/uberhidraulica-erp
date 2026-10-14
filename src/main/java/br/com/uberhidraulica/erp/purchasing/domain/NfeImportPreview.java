package br.com.uberhidraulica.erp.purchasing.domain;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeIssue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Resultado da prévia de importação de NF-e: <b>somente leitura</b>, nada é gravado.
 *
 * <p>É onde o operador vê, antes de confirmar: o que a nota diz, o que a validação achou, se o destinatário é
 * o estabelecimento autorizado (`D9`), se a nota já foi importada (`D5`), a qual produto cada item vai
 * (`D6`), qual fator falta informar (`D7`), quanto vai virar custo (`D1`/`D2`) e quais parcelas nascerão
 * (`D4`/`D11`).</p>
 *
 * <p>{@code confirmable} é falso quando há qualquer {@code blocker}. A prévia continua funcionando mesmo
 * assim — ler não movimenta nada, e é justamente lendo que se descobre o que configurar.</p>
 *
 * @param alreadyImportedPurchaseId compra efetiva que já usa esta chave de acesso, quando existe (`D5`)
 * @param blockers                  motivos que impedem confirmar, em linguagem de operador
 * @param officialCheckPerformed    sempre falso nesta Task: a autorização é a declarada no XML, sem consulta
 *                                  à SEFAZ (exige certificado A1) e sem verificação da assinatura (`D12`)
 */
public record NfeImportPreview(String accessKey, String issuerDocument, String issuerName, String recipientDocument,
                               Long invoiceNumber, String invoiceSeries, LocalDate issuedOn,
                               RecipientCheck.Result recipientCheck, List<NfeIssue> issues,
                               SupplierMatch supplier, List<ItemPreview> items, Totals totals,
                               List<InstallmentPreview> installments, boolean installmentsFromInvoice,
                               UUID alreadyImportedPurchaseId, String xmlSha256, List<String> blockers,
                               boolean confirmable, boolean officialCheckPerformed) {

    public NfeImportPreview {
        issues = issues == null ? List.of() : List.copyOf(issues);
        items = items == null ? List.of() : List.copyOf(items);
        installments = installments == null ? List.of() : List.copyOf(installments);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        confirmable = blockers.isEmpty();
    }

    /**
     * Fornecedor da nota: já cadastrado, ou proposta de cadastro a partir do emitente.
     *
     * @param supplierId     id do fornecedor quando já existe; nulo quando precisa ser cadastrado
     * @param inactive       verdadeiro quando o fornecedor existe mas está inativo
     */
    public record SupplierMatch(UUID supplierId, String document, String legalName, String tradeName,
                                boolean inactive, boolean relatedParty) {}

    /**
     * Um item da nota e o que ainda falta para ele poder ser confirmado.
     *
     * @param matchedBy          como o produto foi encontrado: {@code LINK}, {@code GTIN} ou {@code NONE}
     * @param candidates         sugestões por descrição, apenas como lista — nunca aplicadas sozinhas (`D6`)
     * @param needsFactor        verdadeiro quando a unidade da nota exige fator e ele ainda não é conhecido
     * @param convertedQuantity  quantidade na unidade do catálogo, quando já é calculável
     * @param cost               composição do custo, quando já é calculável
     */
    public record ItemPreview(int lineNo, String supplierCode, String gtin, String description, String ncm,
                              String cfop, String unitOriginal, BigDecimal quantityOriginal, BigDecimal unitPrice,
                              UUID productId, String productDescription, String productUnit, MatchKind matchedBy,
                              List<Candidate> candidates, boolean needsFactor, BigDecimal unitConversionFactor,
                              BigDecimal convertedQuantity, AcquisitionCost cost) {

        public ItemPreview {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** {@code MANUAL} é o produto escolhido pelo operador na prévia: nunca vem de descrição (`D6`). */
        public enum MatchKind { LINK, GTIN, MANUAL, NONE }

        public record Candidate(UUID productId, String description, String internalCode, String unit) {}
    }

    /** Totais da nota conferidos contra a soma dos itens. */
    public record Totals(BigDecimal goods, BigDecimal discount, BigDecimal freight, BigDecimal insurance,
                         BigDecimal other, BigDecimal taxAdded, BigDecimal invoice, BigDecimal itemsSum,
                         boolean closes) {}

    /** Parcela prevista. Vem da duplicata da nota ou foi informada por quem confirma (`D11`). */
    public record InstallmentPreview(int number, LocalDate dueDate, BigDecimal amount, String reference) {}
}
