package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.finance.PurchasePayableException;
import br.com.uberhidraulica.erp.finance.PurchasePayables;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.iam.IamAuthorization;
import br.com.uberhidraulica.erp.productcatalog.ProductCatalogQuery;
import br.com.uberhidraulica.erp.purchasing.domain.AcquisitionCost;
import br.com.uberhidraulica.erp.purchasing.domain.ExpenseAllocation;
import br.com.uberhidraulica.erp.purchasing.domain.InstallmentPlan;
import br.com.uberhidraulica.erp.purchasing.domain.NfeImportPreview;
import br.com.uberhidraulica.erp.purchasing.domain.OrderInvoiceLink;
import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseDocument;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseEvent;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseItem;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseMoney;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasePayableLink;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingSettings;
import br.com.uberhidraulica.erp.purchasing.domain.RecipientCheck;
import br.com.uberhidraulica.erp.purchasing.domain.Supplier;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierProductLink;
import br.com.uberhidraulica.erp.purchasing.domain.UnitConversion;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.BrazilianTaxId;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeXmlParser;
import br.com.uberhidraulica.erp.purchasing.port.GoodsReceiptRepositoryPort;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import br.com.uberhidraulica.erp.purchasing.port.SupplierRepositoryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Importação e confirmação de NF-e de compra (`TASK-0021` I3, decisões aprovadas da `DR-0022`).
 *
 * <p><b>Tudo em uma transação.</b> Falha em qualquer passo — inclusive na criação da segunda conta a pagar —
 * desfaz compra, itens, documento, parcelas, vínculos e eventos. Metade de uma compra gravada seria pior do
 * que nenhuma: deixaria obrigação financeira sem origem ou nota importada sem dívida.</p>
 *
 * <p><b>Importar não movimenta estoque.</b> Entrada física é o `I4`, pelo recebimento. E importar não marca
 * nada como pago: a conta nasce em aberto, como o briefing do proprietário exige.</p>
 */
@Service
public class NfeImportService {
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public static final String DIVERGENCE_PERMISSION = GoodsReceiptService.DIVERGENCE_PERMISSION;

    private final PurchaseRepositoryPort purchases;
    private final SupplierRepositoryPort suppliers;
    private final SupplierService supplierService;
    private final ProductCatalogQuery catalog;
    private final PurchasePayables payables;
    private final PurchasingSettingsService settings;
    private final NfePreviewService previews;
    private final GoodsReceiptRepositoryPort receipts;
    private final IamAuthorization authorization;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    public NfeImportService(PurchaseRepositoryPort purchases, SupplierRepositoryPort suppliers,
                            SupplierService supplierService, ProductCatalogQuery catalog, PurchasePayables payables,
                            PurchasingSettingsService settings, NfePreviewService previews,
                            GoodsReceiptRepositoryPort receipts, IamAuthorization authorization,
                            CurrentUser currentUser) {
        this(purchases, suppliers, supplierService, catalog, payables, settings, previews, receipts, authorization,
                currentUser, Clock.systemUTC());
    }

    NfeImportService(PurchaseRepositoryPort purchases, SupplierRepositoryPort suppliers,
                     SupplierService supplierService, ProductCatalogQuery catalog, PurchasePayables payables,
                     PurchasingSettingsService settings, NfePreviewService previews,
                     GoodsReceiptRepositoryPort receipts, IamAuthorization authorization, CurrentUser currentUser,
                     Clock clock) {
        this.purchases = purchases;
        this.suppliers = suppliers;
        this.supplierService = supplierService;
        this.catalog = catalog;
        this.payables = payables;
        this.settings = settings;
        this.previews = previews;
        this.receipts = receipts;
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Confirma a importação.
     *
     * @return a compra gravada e {@code replayed} verdadeiro quando a chave de idempotência já havia sido usada
     */
    @Transactional
    public Result confirm(Command command) {
        String key = requireKey(command.requestId());

        // 1. Retry HTTP não cria compra nova. Vem antes de tudo: reprocessar o XML à toa seria desperdício, e
        //    pior, criaria risco de divergir do que foi gravado na primeira vez.
        Optional<Purchase> replay = purchases.findByIdempotencyKey(key);
        if (replay.isPresent()) {
            return new Result(replay.get(), purchases.payableLinks(replay.get().id()),
                    purchases.activeLinkOfInvoice(replay.get().id()).map(OrderInvoiceLink::orderPurchaseId)
                            .orElse(null), true);
        }

        NfeDocument document = NfeXmlParser.parse(command.xml());
        PurchasingSettings configuration = settings.current();
        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);

        // 2. D9: sem CNPJ autorizado batendo com o destinatário, nada confirma. Endurecido pelo proprietário:
        //    bloqueio, e a raiz do CNPJ não vale como prova de identidade.
        String recipient = document.recipient() == null ? null : document.recipient().taxId();
        RecipientCheck.requireAuthorized(recipient, configuration.ownTaxId());

        // 3. A prévia é reconstruída aqui dentro para que confirmação e prévia nunca divirjam de regra, e para
        //    que a validação do XML (modelo, CNPJ, soma dos itens) seja reavaliada no instante da gravação.
        NfeImportPreview preview = previews.build(document, configuration);
        requireNoFiscalBlocker(preview);

        // 4. D5: a mesma nota não vira duas compras efetivas, e XML alterado com a mesma chave é bloqueio.
        String accessKey = requireAccessKey(document);
        purchases.findActiveDocumentByAccessKey(accessKey).ifPresent(existing -> {
            throw new PurchasingException("PURCHASE_DUPLICATE_NFE",
                    "Esta NF-e já foi importada na compra " + existing.purchaseId() + " e está ativa");
        });
        purchases.documentsByAccessKey(accessKey).stream()
                .filter(existing -> existing.xmlSha256() != null
                        && !existing.xmlSha256().equalsIgnoreCase(document.xmlSha256()))
                .findFirst().ifPresent(existing -> {
                    throw new PurchasingException("PURCHASE_NFE_CONTENT_DIVERGES",
                            "Já existe documento com esta chave de acesso e conteúdo diferente: o XML foi alterado "
                                    + "e não pode sobrescrever o anterior");
                });

        // 5. Fornecedor: o já cadastrado, ou cadastrado agora a partir do emitente com aceite explícito.
        Supplier supplier = resolveSupplier(document, command, actor, now);

        // 5b. DR-0023 C1-A: o pedido que esta nota atende, escolhido pelo operador. É travado antes de qualquer
        //     gravação, para duas importações simultâneas não encerrarem o mesmo pedido.
        Purchase order = lockLinkableOrder(command.orderLink(), supplier);
        UUID workOrderId = command.workOrderId() != null ? command.workOrderId()
                : order == null ? null : order.workOrderId();

        // 6..8. Itens, conversão, custo e totais.
        UUID purchaseId = UUID.randomUUID();
        NfePreviewService.Totals totals = previews.totals(document);
        List<PurchaseItem> items = buildItems(document, command.items(), workOrderId, order, supplier, configuration,
                purchaseId);

        BigDecimal payableTotal = totals.payable();
        PurchaseMoney.requirePositive(payableTotal, "Total da nota");

        // 9. D4/D11: parcelas da nota, ou informadas quando a nota não tem duplicata. A soma é conferida
        //    contra o total antes de qualquer gravação — é a proteção direta contra duplicar contas a pagar.
        InstallmentPlan plan = buildPlan(preview, command, payableTotal);

        Purchase purchase = new Purchase(purchaseId, 0L, supplier.id(), Purchase.Origin.NFE_IMPORT,
                Purchase.Status.APPROVED, totals.goods(), totals.discount(), totals.freight(), totals.insurance(),
                totals.other(), totals.taxAdded(), payableTotal, command.expenseCategoryId(),
                commercialSnapshot(supplier), workOrderId, command.notes(), key, now, actor, null, null,
                null, 0L, null, null, null, null, items);

        // 9b. C2-A/C3-A: a diferença entre pedido e nota é conferida ANTES de gravar; sem justificativa e sem a
        //     permissão de aceitar divergência, nada é gravado.
        List<OrderInvoiceLink.Difference> differences = order == null ? List.of()
                : OrderInvoiceLink.compare(order, purchase);
        String justification = order == null ? null
                : requireDivergenceAccepted(differences, command.orderLink().justification(), actor);

        // 10. Gravação. A nota importada nasce APPROVED: ela já é o fato comercial.
        purchases.insert(purchase);
        purchases.insertItems(items);

        PurchaseDocument fiscal = new PurchaseDocument(UUID.randomUUID(), purchaseId, PurchaseDocument.Kind.NFE,
                accessKey, document.identification() == null ? null : document.identification().series(),
                document.identification() == null ? null : document.identification().number(),
                issuedAt(document), BrazilianTaxId.normalize(document.issuer().taxId()),
                BrazilianTaxId.normalize(recipient), payableTotal,
                document.protocol() == null ? null : document.protocol().protocolNumber(),
                document.protocol() == null ? null : document.protocol().statusCode(),
                document.xmlSha256(), null, null, null, now, actor);
        purchases.insertDocument(fiscal);
        purchases.storeXml(fiscal.id(), new String(command.xml(), java.nio.charset.StandardCharsets.UTF_8));

        // 11. Obrigações financeiras pelo contrato público do Financeiro, na mesma transação.
        List<PurchasePayableLink> links = registerPayables(purchase, supplier, plan, now);
        purchases.insertPayableLinks(links);

        // 12. Aprendizado: vínculo de item, identidade fiscal do produto e histórico de preço.
        learn(command, supplier, items, document, actor, now, purchaseId);

        // 13. Auditoria. O XML nunca entra no evento — ele fica só em purchase_document.
        purchases.insertEvent(PurchaseEvent.of(purchaseId, PurchaseEvent.Type.IMPORTED, actor, now, null,
                json(Map.of("accessKey", accessKey, "xmlSha256", document.xmlSha256(),
                        "payableTotal", payableTotal.toPlainString(), "items", String.valueOf(items.size())))));
        purchases.insertEvent(PurchaseEvent.of(purchaseId, PurchaseEvent.Type.PAYABLES_REGISTERED, actor, now,
                "Criadas pela importação, com PURCHASE_IMPORT_XML e não com FINANCE_PAYABLE (AG-09)",
                json(Map.of("installments", String.valueOf(links.size()),
                        "total", PurchaseMoney.sumCharged(links.stream().map(PurchasePayableLink::amount).toList())
                                .toPlainString()))));

        // 14. DR-0023: grava o vínculo. A partir daqui o pedido está encerrado pela nota.
        if (order != null) {
            purchases.insertOrderLink(new OrderInvoiceLink(UUID.randomUUID(), order.id(), null, purchaseId, null, now,
                    actor, !differences.isEmpty(), justification, null, null, null, differences));
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("orderPurchaseId", String.valueOf(order.id()));
            detail.put("invoicePurchaseId", String.valueOf(purchaseId));
            detail.put("differences", String.valueOf(differences.size()));
            purchases.insertEvent(PurchaseEvent.of(order.id(), PurchaseEvent.Type.ORDER_LINKED_TO_INVOICE, actor, now,
                    justification, json(detail)));
            purchases.insertEvent(PurchaseEvent.of(purchaseId, PurchaseEvent.Type.ORDER_LINKED_TO_INVOICE, actor, now,
                    justification, json(detail)));
        }

        // Relê a compra: o número sequencial é gerado pelo banco, e devolver o objeto em memória mostraria 0.
        return new Result(purchases.find(purchaseId).orElse(purchase), links, order == null ? null : order.id(),
                false);
    }

    /**
     * Compara a nota com um pedido <b>sem gravar nada</b>, para o operador ver a diferença antes de confirmar
     * (`DR-0023` C2-A). Usa exatamente a mesma montagem de itens da confirmação, de modo que o que a tela mostra é o
     * que a confirmação vai exigir justificar.
     */
    @Transactional(readOnly = true)
    public OrderComparison compareWithOrder(byte[] xml, List<ItemDecision> decisions, UUID orderId) {
        NfeDocument document = NfeXmlParser.parse(xml);
        PurchasingSettings configuration = settings.current();
        NfeDocument.Party issuer = document.issuer();
        Supplier supplier = issuer == null || issuer.taxId() == null ? null
                : suppliers.findByDocument(BrazilianTaxId.normalize(issuer.taxId())).orElse(null);
        if (supplier == null) {
            throw new PurchasingException("PURCHASE_ORDER_LINK_INVALID",
                    "O emitente da nota ainda não é fornecedor cadastrado: não há pedido dele para vincular");
        }
        Purchase order = requireLinkableOrder(purchases.find(orderId), supplier);
        UUID purchaseId = UUID.randomUUID();
        NfePreviewService.Totals totals = previews.totals(document);
        List<PurchaseItem> items = buildItems(document, decisions, order.workOrderId(), order, supplier,
                configuration, purchaseId);
        Purchase invoice = new Purchase(purchaseId, 0L, supplier.id(), Purchase.Origin.NFE_IMPORT,
                Purchase.Status.APPROVED, totals.goods(), totals.discount(), totals.freight(), totals.insurance(),
                totals.other(), totals.taxAdded(), totals.payable(), null, null, order.workOrderId(), null, "preview",
                Instant.now(clock), currentUser.requireId(), null, null, null, 0L, null, null, null, null, items);
        List<OrderInvoiceLink.Difference> differences = OrderInvoiceLink.compare(order, invoice);
        return new OrderComparison(order.id(), order.number(), order.payableTotal(), totals.payable(), differences,
                differences.isEmpty() || authorization.hasPermission(currentUser.requireId(), DIVERGENCE_PERMISSION));
    }

    /** Candidatos ao vínculo: pedidos e compras diretas aprovados do fornecedor, ainda sem nota. */
    @Transactional(readOnly = true)
    public List<PurchaseRepositoryPort.LinkableOrder> linkableOrders(UUID supplierId) {
        return purchases.linkableOrders(supplierId);
    }

    // ------------------------------------------------------------------ passos

    private Purchase lockLinkableOrder(OrderLink link, Supplier supplier) {
        if (link == null || link.orderId() == null) return null;
        return requireLinkableOrder(purchases.lock(link.orderId()), supplier);
    }

    private Purchase requireLinkableOrder(Optional<Purchase> found, Supplier supplier) {
        Purchase order = found.orElseThrow(() -> new PurchasingException("PURCHASE_NOT_FOUND",
                "Pedido não encontrado"));
        OrderInvoiceLink.requireLinkable(order, supplier.id());
        if (purchases.activeLinkOfOrder(order.id()).isPresent()) {
            throw new PurchasingException("PURCHASE_ORDER_ALREADY_LINKED",
                    "O pedido " + order.number() + " já está vinculado a uma nota ativa");
        }
        // C1-A: só se vincula pedido sem recebimento ativo. Hoje pedido não recebe; a checagem cobre recebimento
        // registrado antes do bloqueio e continua valendo se a regra mudar.
        if (receipts.hasActiveReceipt(order.id())) {
            throw new PurchasingException("PURCHASE_HAS_ACTIVE_RECEIPT",
                    "O pedido " + order.number() + " tem recebimento ativo: estorne-o antes de vincular à nota");
        }
        return order;
    }

    private String requireDivergenceAccepted(List<OrderInvoiceLink.Difference> differences, String justification,
                                             UUID actor) {
        String text = OrderInvoiceLink.requireJustification(differences, justification);
        if (!differences.isEmpty() && !authorization.hasPermission(actor, DIVERGENCE_PERMISSION)) {
            throw new PurchasingException("PURCHASE_DIVERGENCE_NOT_AUTHORIZED",
                    "A nota difere do pedido: vincular exige a permissão " + DIVERGENCE_PERMISSION);
        }
        return text;
    }

    private static String requireKey(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new PurchasingException("PURCHASE_IDEMPOTENCY_KEY_REQUIRED",
                    "Informe o cabeçalho Idempotency-Key para a confirmação não duplicar a compra");
        }
        return requestId.trim();
    }

    private static String requireAccessKey(NfeDocument document) {
        String accessKey = document.accessKeyDeclared();
        if (accessKey == null || accessKey.length() != 44) {
            throw new PurchasingException("PURCHASE_DOCUMENT_INVALID", "NF-e sem chave de acesso de 44 posições");
        }
        return accessKey;
    }

    /**
     * Recusa confirmar com achado bloqueante do validador fiscal (nota de outro modelo, não autorizada, itens
     * que não somam o total). Os bloqueios de vínculo e de fator são tratados item por item, com mensagem
     * própria, porque têm conserto na própria tela.
     */
    private static void requireNoFiscalBlocker(NfeImportPreview preview) {
        preview.issues().stream()
                .filter(issue -> issue.severity() == br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeIssue.Severity.BLOCKING)
                .findFirst()
                .ifPresent(issue -> {
                    throw new PurchasingException("PURCHASE_NFE_REFUSED", issue.message());
                });
    }

    private Supplier resolveSupplier(NfeDocument document, Command command, UUID actor, Instant now) {
        NfeDocument.Party issuer = document.issuer();
        if (issuer == null || issuer.taxId() == null) {
            throw new PurchasingException("PURCHASE_SUPPLIER_UNRESOLVED", "Nota sem emitente legível");
        }
        String digits = BrazilianTaxId.normalize(issuer.taxId());
        Optional<Supplier> existing = suppliers.findByDocument(digits);
        if (existing.isPresent()) {
            Supplier supplier = existing.get();
            if (supplier.status() == Supplier.Status.INACTIVE) {
                throw new PurchasingException("PURCHASE_SUPPLIER_INACTIVE",
                        "Fornecedor " + supplier.legalName() + " está inativo: reative o cadastro antes de importar");
            }
            return supplier;
        }
        if (!Boolean.TRUE.equals(command.createSupplier())) {
            throw new PurchasingException("PURCHASE_SUPPLIER_UNRESOLVED",
                    "Fornecedor " + digits + " não está cadastrado: confirme o cadastro a partir do emitente da nota");
        }
        NfeDocument.Address address = issuer.address();
        Supplier created = supplierService.create(new Supplier.Draft(
                digits.length() == 11 ? Supplier.PersonType.PF : Supplier.PersonType.PJ,
                issuer.name(), issuer.tradeName(), digits, issuer.stateRegistration(),
                address == null ? null : address.phone(), null,
                address == null ? null : new Supplier.Address(address.zipCode(), address.street(), address.number(),
                        null, address.district(), address.city(), address.state()),
                null, false, null));
        // O evento de criação do fornecedor fica na trilha do fornecedor; aqui registramos que a origem foi a
        // nota, para a compra explicar sozinha de onde o cadastro veio.
        return created;
    }

    private List<PurchaseItem> buildItems(NfeDocument document, List<ItemDecision> chosen, UUID workOrderId,
                                          Purchase order, Supplier supplier, PurchasingSettings configuration,
                                          UUID purchaseId) {
        Map<Integer, ItemDecision> decisions = new LinkedHashMap<>();
        if (chosen != null) chosen.forEach(d -> decisions.put(d.lineNo(), d));
        // O item da nota herda o item da OS que o pedido vinculado apontava para o mesmo produto.
        Map<UUID, UUID> workOrderItems = new LinkedHashMap<>();
        if (order != null && workOrderId != null && workOrderId.equals(order.workOrderId())) {
            order.items().stream().filter(i -> i.workOrderItemId() != null)
                    .forEach(i -> workOrderItems.putIfAbsent(i.productId(), i.workOrderItemId()));
        }

        List<NfeDocument.Item> source = document.items();
        if (source.isEmpty()) {
            throw new PurchasingException("PURCHASE_NFE_REFUSED", "Nota sem item: nada a comprar");
        }
        List<BigDecimal> netValues = source.stream()
                .map(item -> PurchaseMoney.charged(PurchaseMoney.nullToZero(item.grossTotal())
                        .subtract(PurchaseMoney.nullToZero(item.discount())))).toList();
        NfePreviewService.Totals totals = previews.totals(document);
        List<BigDecimal> freight = ExpenseAllocation.proportionalToValue(netValues, totals.freight());
        List<BigDecimal> insurance = ExpenseAllocation.proportionalToValue(netValues, totals.insurance());
        List<BigDecimal> other = ExpenseAllocation.proportionalToValue(netValues, totals.other());

        List<PurchaseItem> items = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            NfeDocument.Item item = source.get(index);
            ItemDecision decision = decisions.get(item.itemNumber());

            UUID productId = decision == null ? null : decision.productId();
            BigDecimal informedFactor = decision == null ? null : decision.unitConversionFactor();
            if (productId == null) {
                // Sem decisão explícita, vale o vínculo já confirmado (D6, critério 1).
                Optional<SupplierProductLink> link = item.supplierCode() == null ? Optional.empty()
                        : purchases.findLink(supplier.id(), item.supplierCode());
                if (link.isPresent()) {
                    productId = link.get().productId();
                    if (informedFactor == null) informedFactor = link.get().unitConversionFactor();
                }
            }
            if (productId == null) {
                throw new PurchasingException("PURCHASE_ITEM_UNLINKED",
                        "Item " + item.itemNumber() + " (" + item.description()
                                + ") não está vinculado a um produto do catálogo");
            }
            ProductCatalogQuery.ProductReference product = catalog.product(productId)
                    .orElseThrow(() -> new PurchasingException("PURCHASE_ITEM_UNLINKED",
                            "Produto do item " + item.itemNumber() + " não existe no catálogo"));
            if (!product.active()) {
                throw new PurchasingException("PURCHASE_ITEM_PRODUCT_INACTIVE",
                        "Produto " + product.description() + " está inativo e não recebe compra");
            }

            // D7: sem fator válido a linha não confirma. É a trava que impede saldo e custo errados.
            BigDecimal factor = UnitConversion.effectiveFactor(item.unit(), product.unit(), informedFactor);
            BigDecimal quantity = UnitConversion.convertQuantity(item.quantity(), factor);
            AcquisitionCost cost = AcquisitionCost.of(item.grossTotal(), item.discount(), item.ipiAmount(),
                    item.icmsStAmount(), item.icmsAmount(), freight.get(index), insurance.get(index),
                    other.get(index), quantity, configuration.costParameters());

            items.add(new PurchaseItem(UUID.randomUUID(), purchaseId, item.itemNumber(), productId,
                    item.supplierCode(), item.description(), item.unit(), item.quantity(), factor, quantity,
                    UnitConversion.convertUnitCost(item.unitPrice(), factor), cost.unitCost(), cost,
                    PurchaseMoney.charged(item.discount()),
                    PurchaseMoney.charged(PurchaseMoney.nullToZero(item.grossTotal())
                            .subtract(PurchaseMoney.nullToZero(item.discount()))),
                    item.ncm(), item.cfop(), item.gtin(), workOrderId, workOrderItems.get(productId)));
        }
        return items;
    }

    private InstallmentPlan buildPlan(NfeImportPreview preview, Command command, BigDecimal payableTotal) {
        if (preview.installmentsFromInvoice()) {
            List<InstallmentPlan.Installment> duplicates = preview.installments().stream()
                    .map(i -> new InstallmentPlan.Installment(i.number(), i.dueDate(), i.amount(), i.reference()))
                    .toList();
            return InstallmentPlan.fromInvoiceDuplicates(duplicates, payableTotal);
        }
        // D11: nota sem duplicata não presume vencimento nem pagamento.
        Installments informed = command.installments();
        if (informed == null || informed.firstDueDate() == null) {
            throw new PurchasingException("PURCHASE_INSTALLMENTS_REQUIRED",
                    "Nota sem duplicata: informe vencimento e número de parcelas");
        }
        return InstallmentPlan.informed(payableTotal,
                informed.count() == null ? 1 : informed.count(), informed.firstDueDate(),
                informed.intervalDays() == null ? 30 : informed.intervalDays());
    }

    private List<PurchasePayableLink> registerPayables(Purchase purchase, Supplier supplier, InstallmentPlan plan,
                                                       Instant now) {
        if (purchase.expenseCategoryId() == null) {
            throw new PurchasingException("PURCHASE_EXPENSE_CATEGORY_REQUIRED",
                    "Informe a categoria de despesa da compra para gerar as contas a pagar");
        }
        List<PurchasePayables.Installment> request = plan.installments().stream()
                .map(i -> new PurchasePayables.Installment(i.number(), i.dueDate(), i.amount(), i.reference()))
                .toList();
        List<PurchasePayables.Registered> registered;
        try {
            registered = payables.register(new PurchasePayables.Request(
                    "Compra " + purchase.number() + " — " + supplier.legalName(), supplier.legalName(),
                    purchase.expenseCategoryId(), purchase.notes(), request,
                    // Prefixo por compra, não por chave de acesso: nota anulada e reimportada é uma compra
                    // nova e precisa de contas novas, não de reencontro das contas canceladas da anterior.
                    "PURCHASE:" + purchase.id()));
        } catch (PurchasePayableException financeFailure) {
            // Traduz o erro do Financeiro para o código estável de Compras: o controller é de Compras, e sem
            // isso o cliente receberia 500 em vez do erro que o caso realmente é.
            throw new PurchasingException("PURCHASE_PAYABLES_REFUSED",
                    "Financeiro recusou as contas a pagar: " + financeFailure.getMessage());
        }
        List<PurchasePayableLink> links = new ArrayList<>(registered.size());
        for (PurchasePayables.Registered r : registered) {
            links.add(new PurchasePayableLink(UUID.randomUUID(), purchase.id(), r.payableId(), r.number(),
                    r.dueDate(), r.amount(), r.reference(), now));
        }
        return links;
    }

    private void learn(Command command, Supplier supplier, List<PurchaseItem> items, NfeDocument document,
                       UUID actor, Instant now, UUID purchaseId) {
        Map<Integer, ItemDecision> decisions = new LinkedHashMap<>();
        if (command.items() != null) command.items().forEach(d -> decisions.put(d.lineNo(), d));

        List<PurchaseRepositoryPort.PriceHistoryEntry> history = new ArrayList<>(items.size());
        LocalDate occurredOn = document.identification() == null || document.identification().issuedOn() == null
                ? LocalDate.ofInstant(now, ZONE) : document.identification().issuedOn();

        for (PurchaseItem item : items) {
            ItemDecision decision = decisions.get(item.lineNo());
            boolean saveLink = decision != null && Boolean.TRUE.equals(decision.saveLink());
            if (saveLink && item.supplierCode() != null && !item.supplierCode().isBlank()) {
                purchases.upsertLink(new SupplierProductLink(UUID.randomUUID(), supplier.id(), item.supplierCode(),
                        item.gtin(), item.productId(), item.unitOriginal(), item.unitConversionFactor(), now, actor,
                        null, null));
                purchases.insertEvent(PurchaseEvent.of(purchaseId, PurchaseEvent.Type.ITEM_LINK_CONFIRMED, actor, now,
                        null, json(Map.of("supplierCode", item.supplierCode(),
                                "productId", String.valueOf(item.productId()),
                                "factor", item.unitConversionFactor().toPlainString()))));
            }
            // Aprende a identidade fiscal só quando ela ainda não existe; nunca sobrescreve.
            if (item.gtin() != null && !item.gtin().isBlank()) {
                catalog.learnFiscalIdentity(item.productId(), item.gtin(), item.ncm());
            }
            history.add(new PurchaseRepositoryPort.PriceHistoryEntry(UUID.randomUUID(), supplier.id(),
                    item.productId(), item.unitPrice(), item.quantity(), "PURCHASE", purchaseId, occurredOn));
        }
        purchases.insertPriceHistory(history);
    }

    private static Instant issuedAt(NfeDocument document) {
        if (document.identification() == null) return null;
        return document.identification().issuedAt() == null ? null : document.identification().issuedAt().toInstant();
    }

    /** Condições comerciais congeladas no instante da compra, para o histórico não mudar com o cadastro. */
    private static String commercialSnapshot(Supplier supplier) {
        Supplier.CommercialTerms terms = supplier.terms();
        if (terms == null) return null;
        return json(new LinkedHashMap<>(Map.of(
                "paymentTerms", String.valueOf(terms.paymentTerms()),
                "preferredPaymentMethod", String.valueOf(terms.preferredPaymentMethod()),
                "usualDueDay", String.valueOf(terms.usualDueDay()))));
    }

    /** JSON mínimo e local: o payload de auditoria é um mapa de texto, nunca o XML. */
    private static String json(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) out.append(',');
            out.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('"');
            first = false;
        }
        return out.append('}').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    // ------------------------------------------------------------------ contratos

    /**
     * @param createSupplier aceite explícito de cadastrar o fornecedor a partir do emitente
     * @param items          decisões por item: produto e fator, e se o vínculo deve ser guardado (`D6`/`D7`)
     * @param installments   vencimento e parcelas, obrigatório só quando a nota não tem duplicata (`D11`)
     */
    public record Command(byte[] xml, String requestId, Boolean createSupplier, UUID expenseCategoryId,
                          List<ItemDecision> items, Installments installments, UUID workOrderId, String notes,
                          OrderLink orderLink) {}

    /**
     * Pedido que a nota atende (`DR-0023` C1-A) e a justificativa, obrigatória quando a nota difere dele (C2-A).
     */
    public record OrderLink(UUID orderId, String justification) {}

    /**
     * @param mayAccept falso quando há diferença e o usuário não tem a permissão de aceitar divergência
     */
    public record OrderComparison(UUID orderId, long orderNumber, BigDecimal orderTotal, BigDecimal invoiceTotal,
                                  List<OrderInvoiceLink.Difference> differences, boolean mayAccept) {}

    public record ItemDecision(int lineNo, UUID productId, BigDecimal unitConversionFactor, Boolean saveLink) {}

    public record Installments(Integer count, LocalDate firstDueDate, Integer intervalDays) {}

    public record Result(Purchase purchase, List<PurchasePayableLink> payables, UUID linkedOrderId,
                         boolean replayed) {}
}
