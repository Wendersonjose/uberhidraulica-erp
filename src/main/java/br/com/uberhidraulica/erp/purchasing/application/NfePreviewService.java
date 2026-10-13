package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.purchasing.domain.AcquisitionCost;
import br.com.uberhidraulica.erp.purchasing.domain.ExpenseAllocation;
import br.com.uberhidraulica.erp.purchasing.domain.NfeImportPreview;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseMoney;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingSettings;
import br.com.uberhidraulica.erp.purchasing.domain.RecipientCheck;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierProductLink;
import br.com.uberhidraulica.erp.purchasing.domain.UnitConversion;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.BrazilianTaxId;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeIssue;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeValidator;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeXmlParser;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Prévia da importação de NF-e: lê o XML e mostra o que aconteceria, <b>sem gravar nada</b>.
 *
 * <p>É onde o operador descobre o que falta antes de confirmar. Por isso a prévia funciona mesmo quando a
 * confirmação está bloqueada — inclusive quando o {@code OWN_TAX_ID} não está configurado: ler não movimenta
 * estoque, não cria obrigação e não altera cadastro.</p>
 */
@Service
public class NfePreviewService {
    /** Quantas sugestões por descrição mostrar. Lista curta: a decisão é humana, não um menu infinito (`D6`). */
    private static final int MAX_CANDIDATES = 5;
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final PurchaseRepositoryPort purchases;
    private final SupplierRepositoryPort suppliers;
    private final ProductCatalogQuery catalog;
    private final PurchasingSettingsService settings;
    private final Clock clock;

    @Autowired
    public NfePreviewService(PurchaseRepositoryPort purchases, SupplierRepositoryPort suppliers,
                             ProductCatalogQuery catalog, PurchasingSettingsService settings) {
        this(purchases, suppliers, catalog, settings, Clock.systemUTC());
    }

    NfePreviewService(PurchaseRepositoryPort purchases, SupplierRepositoryPort suppliers,
                      ProductCatalogQuery catalog, PurchasingSettingsService settings, Clock clock) {
        this.purchases = purchases;
        this.suppliers = suppliers;
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NfeImportPreview preview(byte[] xml) {
        NfeDocument document = NfeXmlParser.parse(xml);
        PurchasingSettings configuration = settings.current();
        return build(document, configuration);
    }

    /** Montagem compartilhada com a confirmação, para prévia e confirmação não divergirem de regra. */
    NfeImportPreview build(NfeDocument document, PurchasingSettings configuration) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
        String ownTaxId = configuration.ownTaxId();
        List<NfeIssue> issues = NfeValidator.validate(document, new NfeValidator.Context(ownTaxId, today));

        String recipientDocument = document.recipient() == null ? null : document.recipient().taxId();
        RecipientCheck.Result recipient = RecipientCheck.verify(recipientDocument, ownTaxId);

        List<String> blockers = new ArrayList<>();
        if (NfeValidator.hasBlocking(issues)) {
            issues.stream().filter(i -> i.severity() == NfeIssue.Severity.BLOCKING)
                    .forEach(i -> blockers.add(i.message()));
        }
        if (recipient != RecipientCheck.Result.AUTHORIZED) blockers.add(RecipientCheck.explain(recipient));

        // D5: nota já importada e ativa não vira segunda compra; nota com o mesmo número de chave e hash
        // diferente é nota alterada, e isso é bloqueio, não aviso.
        UUID alreadyImported = null;
        String accessKey = document.accessKeyDeclared();
        if (accessKey != null) {
            Optional<br.com.uberhidraulica.erp.purchasing.domain.PurchaseDocument> active =
                    purchases.findActiveDocumentByAccessKey(accessKey);
            if (active.isPresent()) {
                alreadyImported = active.get().purchaseId();
                blockers.add("Esta NF-e já foi importada e a compra está ativa");
            }
            purchases.documentsByAccessKey(accessKey).stream()
                    .filter(existing -> existing.xmlSha256() != null
                            && !existing.xmlSha256().equalsIgnoreCase(document.xmlSha256()))
                    .findFirst()
                    .ifPresent(existing -> blockers.add(
                            "Já existe documento com esta chave de acesso e conteúdo diferente: o XML foi alterado"));
        }

        NfeImportPreview.SupplierMatch supplier = matchSupplier(document, blockers);
        Totals totals = totals(document);
        List<NfeImportPreview.ItemPreview> items =
                items(document, supplier, configuration, totals, blockers);
        List<NfeImportPreview.InstallmentPreview> installments = installments(document);
        boolean fromInvoice = !installments.isEmpty();
        if (!fromInvoice) {
            // D11: nada é presumido. Não é bloqueio da prévia — é informação de que a confirmação vai pedir.
            blockers.add("Nota sem duplicata: informe vencimento e condições de pagamento na confirmação");
        }

        return new NfeImportPreview(accessKey,
                document.issuer() == null ? null : document.issuer().taxId(),
                document.issuer() == null ? null : document.issuer().name(),
                recipientDocument,
                document.identification() == null ? null : document.identification().number(),
                document.identification() == null ? null : document.identification().series(),
                document.identification() == null ? null : document.identification().issuedOn(),
                recipient, issues, supplier, items, totals.toPreview(), installments, fromInvoice,
                alreadyImported, document.xmlSha256(), blockers, false, false);
    }

    // ------------------------------------------------------------------ fornecedor

    private NfeImportPreview.SupplierMatch matchSupplier(NfeDocument document, List<String> blockers) {
        NfeDocument.Party issuer = document.issuer();
        if (issuer == null || issuer.taxId() == null) {
            blockers.add("Nota sem emitente legível: não é possível identificar o fornecedor");
            return new NfeImportPreview.SupplierMatch(null, null, null, null, false, false);
        }
        String digits = BrazilianTaxId.normalize(issuer.taxId());
        Optional<Supplier> existing = suppliers.findByDocument(digits);
        if (existing.isEmpty()) {
            // Não é bloqueio: a confirmação pode cadastrar o fornecedor a partir do emitente, com aceite.
            return new NfeImportPreview.SupplierMatch(null, digits, issuer.name(), issuer.tradeName(), false, false);
        }
        Supplier supplier = existing.get();
        boolean inactive = supplier.status() == Supplier.Status.INACTIVE;
        if (inactive) {
            blockers.add("Fornecedor " + supplier.legalName()
                    + " está inativo: reative o cadastro antes de importar a nota");
        }
        return new NfeImportPreview.SupplierMatch(supplier.id(), supplier.document(), supplier.legalName(),
                supplier.tradeName(), inactive, supplier.relatedParty());
    }

    // ------------------------------------------------------------------ itens

    private List<NfeImportPreview.ItemPreview> items(NfeDocument document,
                                                     NfeImportPreview.SupplierMatch supplier,
                                                     PurchasingSettings configuration, Totals totals,
                                                     List<String> blockers) {
        List<NfeDocument.Item> source = document.items();
        List<BigDecimal> netValues = source.stream()
                .map(item -> PurchaseMoney.charged(PurchaseMoney.nullToZero(item.grossTotal())
                        .subtract(PurchaseMoney.nullToZero(item.discount()))))
                .toList();
        // D2: rateio proporcional ao valor, com fechamento exato. Calculado aqui para a prévia já mostrar o
        // custo que vai entrar no estoque — é o número que o operador precisa ver antes de confirmar.
        List<BigDecimal> freightShares = allocate(netValues, totals.freight());
        List<BigDecimal> insuranceShares = allocate(netValues, totals.insurance());
        List<BigDecimal> otherShares = allocate(netValues, totals.other());

        List<NfeImportPreview.ItemPreview> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            NfeDocument.Item item = source.get(index);
            Resolution resolution = resolve(supplier.supplierId(), item);

            BigDecimal factor = null;
            BigDecimal quantity = null;
            AcquisitionCost cost = null;
            boolean needsFactor = resolution.productUnit() == null;
            if (resolution.productUnit() != null) {
                // Falta de fator é estado esperado da prévia, não erro — mas só ela. Qualquer outro problema
                // do item (desconto maior que o valor, por exemplo) tem de aparecer como bloqueio próprio, e
                // não ser confundido com "falta fator", senão o operador informa um fator e nada melhora.
                if (resolution.factor() == null
                        && !UnitConversion.needsNoFactor(item.unit(), resolution.productUnit())) {
                    needsFactor = true;
                } else {
                    factor = UnitConversion.effectiveFactor(item.unit(), resolution.productUnit(),
                            resolution.factor());
                    quantity = UnitConversion.convertQuantity(item.quantity(), factor);
                    try {
                        cost = AcquisitionCost.of(item.grossTotal(), item.discount(), item.ipiAmount(),
                                item.icmsStAmount(), item.icmsAmount(), freightShares.get(index),
                                insuranceShares.get(index), otherShares.get(index), quantity,
                                configuration.costParameters());
                    } catch (PurchasingException inconsistent) {
                        blockers.add("Item " + item.itemNumber() + ": " + inconsistent.getMessage());
                    }
                }
            }
            if (resolution.productId() == null) {
                blockers.add("Item " + item.itemNumber() + " (" + item.description()
                        + ") não está vinculado a um produto do catálogo");
            } else if (needsFactor) {
                blockers.add("Item " + item.itemNumber() + ": unidade " + UnitConversion.normalize(item.unit())
                        + " da nota exige fator de conversão para " + resolution.productUnit());
            }

            result.add(new NfeImportPreview.ItemPreview(item.itemNumber(), item.supplierCode(), item.gtin(),
                    item.description(), item.ncm(), item.cfop(), item.unit(), item.quantity(), item.unitPrice(),
                    resolution.productId(), resolution.productDescription(), resolution.productUnit(),
                    resolution.matchedBy(), resolution.candidates(), needsFactor, factor, quantity, cost));
        }
        return result;
    }

    private static List<BigDecimal> allocate(List<BigDecimal> netValues, BigDecimal amount) {
        if (netValues.isEmpty()) return List.of();
        return ExpenseAllocation.proportionalToValue(netValues, amount);
    }

    /**
     * Casamento de item na ordem da `D6`: vínculo já confirmado, depois GTIN, depois candidatos por descrição
     * apenas como lista. Nada por descrição é aplicado sozinho.
     */
    private Resolution resolve(UUID supplierId, NfeDocument.Item item) {
        if (supplierId != null && item.supplierCode() != null) {
            Optional<SupplierProductLink> link = purchases.findLink(supplierId, item.supplierCode());
            if (link.isPresent()) {
                Optional<ProductCatalogQuery.ProductReference> product = catalog.product(link.get().productId());
                if (product.isPresent()) {
                    return new Resolution(product.get().id(), product.get().description(), product.get().unit(),
                            NfeImportPreview.ItemPreview.MatchKind.LINK, link.get().unitConversionFactor(), List.of());
                }
            }
        }
        if (item.gtin() != null && !item.gtin().isBlank()) {
            Optional<ProductCatalogQuery.ProductReference> byGtin = catalog.productByGtin(item.gtin());
            if (byGtin.isPresent()) {
                return new Resolution(byGtin.get().id(), byGtin.get().description(), byGtin.get().unit(),
                        NfeImportPreview.ItemPreview.MatchKind.GTIN, null, List.of());
            }
        }
        return new Resolution(null, null, null, NfeImportPreview.ItemPreview.MatchKind.NONE, null,
                candidates(item.description()));
    }

    /** Sugestões por descrição: lista para o operador escolher, nunca casamento automático (`D6`). */
    private List<NfeImportPreview.ItemPreview.Candidate> candidates(String description) {
        if (description == null || description.isBlank()) return List.of();
        List<String> tokens = java.util.Arrays.stream(description.toLowerCase(Locale.ROOT).split("[^0-9a-zà-ú]+"))
                .filter(token -> token.length() >= 3).toList();
        if (tokens.isEmpty()) return List.of();
        record Scored(ProductCatalogQuery.ProductReference product, long score) {}
        return catalog.products().stream()
                .filter(ProductCatalogQuery.ProductReference::active)
                .map(product -> new Scored(product, tokens.stream()
                        .filter(token -> product.description().toLowerCase(Locale.ROOT).contains(token)).count()))
                .filter(scored -> scored.score() > 0)
                .sorted((a, b) -> b.score() != a.score() ? Long.compare(b.score(), a.score())
                        : a.product().description().compareToIgnoreCase(b.product().description()))
                .limit(MAX_CANDIDATES)
                .map(scored -> new NfeImportPreview.ItemPreview.Candidate(scored.product().id(),
                        scored.product().description(), scored.product().internalCode(), scored.product().unit()))
                .toList();
    }

    // ------------------------------------------------------------------ totais e parcelas

    Totals totals(NfeDocument document) {
        NfeDocument.Totals t = document.totals();
        BigDecimal goods = PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.productsAmount());
        BigDecimal discount = PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.discount());
        BigDecimal freight = PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.freight());
        BigDecimal insurance = PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.insurance());
        BigDecimal other = PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.otherExpenses());
        // "Tributo somado" é o que a nota acrescenta ao valor cobrado: IPI e ICMS-ST. Não é o custo (D1),
        // que depende dos parâmetros; é a composição do total da própria nota.
        BigDecimal taxAdded = PurchaseMoney.sumCharged(List.of(
                PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.ipiAmount()),
                PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.icmsStAmount())));
        BigDecimal invoice = PurchaseMoney.charged(t == null ? BigDecimal.ZERO : t.invoiceAmount());
        BigDecimal itemsSum = PurchaseMoney.sumCharged(document.items().stream()
                .map(item -> PurchaseMoney.charged(PurchaseMoney.nullToZero(item.grossTotal())
                        .subtract(PurchaseMoney.nullToZero(item.discount())))).toList());
        return new Totals(goods, discount, freight, insurance, other, taxAdded, invoice, itemsSum);
    }

    private static List<NfeImportPreview.InstallmentPreview> installments(NfeDocument document) {
        NfeDocument.Billing billing = document.billing();
        if (billing == null || billing.installments() == null || billing.installments().isEmpty()) return List.of();
        List<NfeImportPreview.InstallmentPreview> result = new ArrayList<>();
        List<NfeDocument.Installment> source = billing.installments();
        for (int i = 0; i < source.size(); i++) {
            NfeDocument.Installment installment = source.get(i);
            result.add(new NfeImportPreview.InstallmentPreview(i + 1, installment.dueDate(),
                    PurchaseMoney.charged(installment.amount()), installment.number()));
        }
        return result;
    }

    /** Totais da nota, com a soma dos itens para conferência. */
    record Totals(BigDecimal goods, BigDecimal discount, BigDecimal freight, BigDecimal insurance,
                  BigDecimal other, BigDecimal taxAdded, BigDecimal invoice, BigDecimal itemsSum) {

        /** O total a pagar é o da nota: ele é o fato comercial, não um número recalculado por nós. */
        BigDecimal payable() { return invoice; }

        boolean closes() {
            BigDecimal composed = PurchaseMoney.sumCharged(List.of(itemsSum, freight, insurance, other, taxAdded));
            return composed.compareTo(invoice) == 0;
        }

        NfeImportPreview.Totals toPreview() {
            return new NfeImportPreview.Totals(goods, discount, freight, insurance, other, taxAdded, invoice,
                    itemsSum, closes());
        }
    }

    private record Resolution(UUID productId, String productDescription, String productUnit,
                              NfeImportPreview.ItemPreview.MatchKind matchedBy, BigDecimal factor,
                              List<NfeImportPreview.ItemPreview.Candidate> candidates) {}
}
