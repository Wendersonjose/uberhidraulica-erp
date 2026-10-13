package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Installment;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Item;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Totals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Regras de consistência de uma NF-e lida. Não substitui a consulta aos serviços oficiais: a autorização declarada
 * no XML é apenas informação, e a ausência dessa verificação é sempre reportada como {@code INFO}.
 */
public final class NfeValidator {
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final Set<String> AUTHORIZED_STATUS = Set.of("100", "150");

    private NfeValidator() {}

    /**
     * @param ownTaxId CNPJ da oficina (instalação); nulo quando ainda não configurado
     * @param today    data de referência para detectar emissão futura
     */
    public record Context(String ownTaxId, LocalDate today) {}

    public static List<NfeIssue> validate(NfeDocument document, Context context) {
        List<NfeIssue> issues = new ArrayList<>();
        identification(document, issues);
        parties(document, context, issues);
        authorization(document, issues);
        items(document, issues);
        totals(document, issues);
        billing(document, issues);
        if (context.today() != null && document.identification().issuedOn() != null
                && document.identification().issuedOn().isAfter(context.today().plusDays(1)))
            issues.add(NfeIssue.warning("NFE_ISSUED_IN_FUTURE", "Data de emissão posterior à data de hoje"));
        return issues;
    }

    public static boolean hasBlocking(List<NfeIssue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == NfeIssue.Severity.BLOCKING);
    }

    // ------------------------------------------------------------------ identificação e chave

    private static void identification(NfeDocument d, List<NfeIssue> issues) {
        NfeDocument.Identification ide = d.identification();
        if (!"55".equals(ide.model()))
            issues.add(NfeIssue.blocking("NFE_MODEL_NOT_55", "Somente NF-e modelo 55 pode ser importada (modelo informado: " + ide.model() + ")"));
        if (ide.issuedOn() == null) issues.add(NfeIssue.blocking("NFE_ISSUE_DATE_MISSING", "Data de emissão ausente"));
        if (!"1".equals(ide.direction()))
            issues.add(NfeIssue.warning("NFE_DIRECTION_NOT_OUTPUT", "tpNF diferente de 1 (saída): a nota não foi emitida como venda pelo emitente"));
        if (ide.purpose() != null && !"1".equals(ide.purpose()))
            issues.add(NfeIssue.warning("NFE_PURPOSE_NOT_NORMAL", "Finalidade da NF-e (finNFe=" + ide.purpose() + ") não é normal: complementar, ajuste ou devolução"));

        if (d.accessKeyDeclared() == null) {
            issues.add(NfeIssue.blocking("NFE_KEY_MISSING", "Chave de acesso ausente (atributo Id de infNFe)"));
            return;
        }
        if (!NfeAccessKey.isValid(d.accessKeyDeclared())) {
            issues.add(NfeIssue.blocking("NFE_KEY_INVALID", "Chave de acesso inválida: formato ou dígito verificador não conferem"));
            return;
        }
        NfeAccessKey key = NfeAccessKey.parse(d.accessKeyDeclared());
        List<String> divergences = new ArrayList<>();
        if (!key.stateCode().equals(ide.stateCode())) divergences.add("UF");
        if (ide.issuedOn() != null && !key.yearMonth().equals(String.format("%02d%02d", ide.issuedOn().getYear() % 100, ide.issuedOn().getMonthValue())))
            divergences.add("ano/mês de emissão");
        if (d.issuer() != null && d.issuer().taxId() != null && !key.issuerTaxId().equals(padTaxId(d.issuer().taxId())))
            divergences.add("CNPJ/CPF do emitente");
        if (!key.model().equals(ide.model())) divergences.add("modelo");
        if (ide.series() == null || !sameNumber(key.series(), ide.series())) divergences.add("série");
        if (ide.number() == null || key.number() != ide.number()) divergences.add("número");
        if (!key.emissionType().equals(ide.emissionType())) divergences.add("tipo de emissão");
        if (!key.numericCode().equals(ide.numericCode())) divergences.add("código numérico");
        if (!String.valueOf(key.checkDigit()).equals(ide.checkDigit())) divergences.add("dígito verificador (cDV)");
        if (!divergences.isEmpty())
            issues.add(NfeIssue.blocking("NFE_KEY_MISMATCH", "A chave de acesso não confere com o conteúdo da nota: " + String.join(", ", divergences)));
    }

    // ------------------------------------------------------------------ partes

    private static void parties(NfeDocument d, Context context, List<NfeIssue> issues) {
        String issuer = d.issuer() == null ? null : d.issuer().taxId();
        String recipient = d.recipient() == null ? null : d.recipient().taxId();
        if (issuer == null || !BrazilianTaxId.isValid(issuer))
            issues.add(NfeIssue.blocking("NFE_ISSUER_TAX_ID_INVALID", "CNPJ/CPF do emitente ausente ou inválido"));
        if (d.recipient() == null || recipient == null || !BrazilianTaxId.isValid(recipient))
            issues.add(NfeIssue.blocking("NFE_RECIPIENT_TAX_ID_INVALID", "CNPJ/CPF do destinatário ausente ou inválido"));

        String own = BrazilianTaxId.normalize(context.ownTaxId());
        if (own == null) {
            issues.add(NfeIssue.warning("NFE_OWN_TAX_ID_NOT_CONFIGURED",
                    "CNPJ da oficina não configurado: não foi possível confirmar que a nota é destinada a esta empresa"));
            return;
        }
        if (recipient != null && !own.equals(recipient))
            issues.add(NfeIssue.blocking("NFE_RECIPIENT_NOT_THIS_COMPANY",
                    "A nota é destinada a " + BrazilianTaxId.format(recipient) + ", e não ao CNPJ desta oficina (" + BrazilianTaxId.format(own) + ")"));
        if (issuer != null && own.equals(issuer))
            issues.add(NfeIssue.blocking("NFE_ISSUER_IS_THIS_COMPANY", "O emitente é a própria oficina: esta nota não é uma compra"));
        else if (issuer != null && issuer.length() == 14 && own.length() == 14 && issuer.substring(0, 8).equals(own.substring(0, 8)))
            issues.add(NfeIssue.warning("NFE_SAME_LEGAL_ENTITY_ROOT",
                    "Emitente e destinatário têm a mesma raiz de CNPJ (mesma pessoa jurídica): pode ser transferência entre estabelecimentos, não compra"));
        else if (issuer != null)
            issues.add(NfeIssue.info("NFE_DISTINCT_LEGAL_ENTITY", "Emitente é pessoa jurídica distinta da destinatária (raízes de CNPJ diferentes)"));
    }

    // ------------------------------------------------------------------ autorização declarada

    private static void authorization(NfeDocument d, List<NfeIssue> issues) {
        String environment = d.identification().environment();
        if ("2".equals(environment))
            issues.add(NfeIssue.blocking("NFE_HOMOLOGATION", "Nota emitida em ambiente de homologação (tpAmb=2): sem valor fiscal"));
        else if (environment == null)
            issues.add(NfeIssue.warning("NFE_ENVIRONMENT_MISSING", "Ambiente de emissão (tpAmb) ausente"));

        NfeDocument.Protocol protocol = d.protocol();
        if (protocol == null) {
            issues.add(NfeIssue.blocking("NFE_PROTOCOL_MISSING", "XML sem protocolo de autorização (protNFe): a nota não prova ter sido autorizada"));
        } else {
            if (protocol.accessKey() == null || !protocol.accessKey().equals(d.accessKeyDeclared()))
                issues.add(NfeIssue.blocking("NFE_PROTOCOL_KEY_MISMATCH", "A chave do protocolo difere da chave da nota"));
            if (protocol.statusCode() == null || !AUTHORIZED_STATUS.contains(protocol.statusCode()))
                issues.add(NfeIssue.blocking("NFE_NOT_AUTHORIZED", "Situação declarada no protocolo não é de uso autorizado (cStat="
                        + protocol.statusCode() + (protocol.statusReason() == null ? "" : " - " + protocol.statusReason()) + ")"));
            if (protocol.protocolNumber() == null)
                issues.add(NfeIssue.warning("NFE_PROTOCOL_NUMBER_MISSING", "Número do protocolo de autorização ausente"));
            if (protocol.environment() != null && environment != null && !protocol.environment().equals(environment))
                issues.add(NfeIssue.warning("NFE_PROTOCOL_ENVIRONMENT_MISMATCH", "Ambiente do protocolo difere do ambiente da nota"));
        }
        if (d.hasSignature())
            issues.add(NfeIssue.info("NFE_SIGNATURE_NOT_VERIFIED", "Assinatura digital presente, mas sua validade criptográfica não foi verificada"));
        else
            issues.add(NfeIssue.warning("NFE_SIGNATURE_ABSENT", "XML sem assinatura digital"));
        issues.add(NfeIssue.info("NFE_OFFICIAL_VALIDATION_NOT_PERFORMED",
                "A autorização e a situação da nota são as declaradas no XML; a consulta aos serviços oficiais da SEFAZ não foi realizada"));
    }

    // ------------------------------------------------------------------ itens

    private static void items(NfeDocument d, List<NfeIssue> issues) {
        if (d.items().isEmpty()) {
            issues.add(NfeIssue.blocking("NFE_NO_ITEMS", "A nota não possui itens"));
            return;
        }
        Set<Integer> numbers = new HashSet<>();
        int expected = 1;
        boolean sequential = true;
        for (Item item : d.items()) {
            if (!numbers.add(item.itemNumber()))
                issues.add(NfeIssue.blocking("NFE_ITEM_DUPLICATED", "Número de item repetido", item.itemNumber()));
            if (item.itemNumber() != expected++) sequential = false;
            item(item, issues);
        }
        if (!sequential) issues.add(NfeIssue.warning("NFE_ITEM_NUMBERING", "Numeração dos itens (nItem) fora de sequência"));
    }

    private static void item(Item item, List<NfeIssue> issues) {
        int n = item.itemNumber();
        if (blank(item.supplierCode()) || blank(item.description()) || blank(item.unit()))
            issues.add(NfeIssue.blocking("NFE_ITEM_INCOMPLETE", "Item sem código, descrição ou unidade", n));
        if (item.quantity() == null || item.quantity().signum() <= 0)
            issues.add(NfeIssue.blocking("NFE_ITEM_QUANTITY_INVALID", "Quantidade ausente ou não positiva", n));
        if (item.unitPrice() == null || item.unitPrice().signum() < 0 || item.grossTotal() == null || item.grossTotal().signum() < 0)
            issues.add(NfeIssue.blocking("NFE_ITEM_VALUE_INVALID", "Valor unitário ou total do item ausente ou negativo", n));
        if (item.quantity() != null && item.unitPrice() != null && item.grossTotal() != null) {
            BigDecimal computed = item.quantity().multiply(item.unitPrice()).setScale(2, RoundingMode.HALF_UP);
            if (computed.subtract(item.grossTotal()).abs().compareTo(ONE_CENT) > 0)
                issues.add(NfeIssue.warning("NFE_ITEM_TOTAL_MISMATCH",
                        "vProd (" + item.grossTotal().toPlainString() + ") difere de quantidade × valor unitário (" + computed.toPlainString() + ")", n));
        }
        if (item.ncm() == null || !item.ncm().matches("[0-9]{8}"))
            issues.add(NfeIssue.warning("NFE_ITEM_NCM_INVALID", "NCM ausente ou fora do formato de 8 dígitos", n));
        if (item.cfop() == null || !item.cfop().matches("[0-9]{4}"))
            issues.add(NfeIssue.warning("NFE_ITEM_CFOP_INVALID", "CFOP ausente ou fora do formato de 4 dígitos", n));
        else if (item.cfop().charAt(0) != '5' && item.cfop().charAt(0) != '6' && item.cfop().charAt(0) != '7')
            issues.add(NfeIssue.warning("NFE_ITEM_CFOP_UNEXPECTED", "CFOP " + item.cfop() + " não é de saída (5/6/7), esperado para o emitente", n));
        if (blank(item.icmsCst()) && blank(item.icmsCsosn()))
            issues.add(NfeIssue.warning("NFE_ITEM_TAX_SITUATION_MISSING", "Item sem CST/CSOSN de ICMS", n));
        if (item.gtinDeclared() != null && item.gtin() == null && item.gtinDeclared().matches("[0-9]+"))
            issues.add(NfeIssue.warning("NFE_ITEM_GTIN_INVALID", "GTIN informado (" + item.gtinDeclared() + ") com dígito verificador inválido; será ignorado", n));
        if (!item.includedInTotal())
            issues.add(NfeIssue.info("NFE_ITEM_NOT_IN_TOTAL", "Item não compõe o valor total da nota (indTot=0)", n));
    }

    // ------------------------------------------------------------------ totais

    private static void totals(NfeDocument d, List<NfeIssue> issues) {
        Totals t = d.totals();
        if (t == null || t.productsAmount() == null || t.invoiceAmount() == null) {
            issues.add(NfeIssue.blocking("NFE_TOTALS_MISSING", "Totais da nota (ICMSTot: vProd/vNF) ausentes"));
            return;
        }
        if (t.invoiceAmount().signum() <= 0) issues.add(NfeIssue.blocking("NFE_TOTAL_NOT_POSITIVE", "Valor total da nota (vNF) não positivo"));

        BigDecimal sumProducts = sum(d.items().stream().filter(Item::includedInTotal).map(Item::grossTotal).toList());
        if (sumProducts.compareTo(t.productsAmount()) != 0)
            issues.add(NfeIssue.blocking("NFE_TOTAL_PRODUCTS_MISMATCH", "Soma dos itens (" + sumProducts.toPlainString()
                    + ") difere do total de produtos da nota (" + t.productsAmount().toPlainString() + ")"));
        compareSum(d, Item::freight, t.freight(), "FREIGHT", "frete", issues);
        compareSum(d, Item::insurance, t.insurance(), "INSURANCE", "seguro", issues);
        compareSum(d, Item::discount, t.discount(), "DISCOUNT", "desconto", issues);
        compareSum(d, Item::otherExpenses, t.otherExpenses(), "OTHER_EXPENSES", "outras despesas", issues);
        compareSum(d, Item::ipiAmount, t.ipiAmount(), "IPI", "IPI", issues);
        compareSum(d, Item::icmsStAmount, t.icmsStAmount(), "ICMS_ST", "ICMS ST", issues);

        BigDecimal expected = nz(t.productsAmount()).subtract(nz(t.discount())).subtract(nz(t.icmsExempted()))
                .add(nz(t.icmsStAmount())).add(nz(t.fcpStAmount())).add(nz(t.freight())).add(nz(t.insurance()))
                .add(nz(t.otherExpenses())).add(nz(t.importTax())).add(nz(t.ipiAmount())).add(nz(t.ipiReturned()));
        if (expected.subtract(t.invoiceAmount()).abs().compareTo(ONE_CENT) > 0)
            issues.add(NfeIssue.warning("NFE_TOTAL_INVOICE_FORMULA_MISMATCH", "vNF (" + t.invoiceAmount().toPlainString()
                    + ") difere da composição dos totais (" + expected.toPlainString() + ")"));
    }

    private static void compareSum(NfeDocument d, java.util.function.Function<Item, BigDecimal> field, BigDecimal declared,
                                   String code, String label, List<NfeIssue> issues) {
        if (d.items().stream().noneMatch(i -> field.apply(i) != null)) return;
        BigDecimal sum = sum(d.items().stream().map(field).toList());
        if (sum.compareTo(nz(declared)) != 0)
            issues.add(NfeIssue.warning("NFE_TOTAL_" + code + "_MISMATCH", "Soma dos itens para " + label + " (" + sum.toPlainString()
                    + ") difere do total declarado (" + nz(declared).toPlainString() + ")"));
    }

    // ------------------------------------------------------------------ cobrança

    private static void billing(NfeDocument d, List<NfeIssue> issues) {
        NfeDocument.Billing billing = d.billing();
        if (billing == null || billing.installments().isEmpty()) {
            issues.add(NfeIssue.info("NFE_NO_BILLING_INSTALLMENTS", "A nota não traz duplicatas: o pagamento deve ser informado manualmente"));
            return;
        }
        boolean complete = true;
        Set<String> numbers = new HashSet<>();
        for (Installment installment : billing.installments()) {
            if (installment.amount() == null || installment.amount().signum() <= 0 || installment.dueDate() == null) {
                complete = false;
                issues.add(NfeIssue.blocking("NFE_INSTALLMENT_INVALID", "Duplicata sem valor positivo ou sem vencimento"));
                continue;
            }
            if (installment.number() != null && !numbers.add(installment.number()))
                issues.add(NfeIssue.warning("NFE_INSTALLMENT_NUMBER_REPEATED", "Número de duplicata repetido: " + installment.number()));
            LocalDate issued = d.identification().issuedOn();
            if (issued != null && installment.dueDate().isBefore(issued))
                issues.add(NfeIssue.warning("NFE_INSTALLMENT_BEFORE_ISSUE", "Duplicata vence antes da emissão da nota: " + installment.dueDate()));
        }
        if (!complete || d.totals() == null || d.totals().invoiceAmount() == null) return;
        BigDecimal sum = sum(billing.installments().stream().map(Installment::amount).toList());
        if (sum.compareTo(d.totals().invoiceAmount()) != 0)
            issues.add(NfeIssue.warning("NFE_INSTALLMENTS_SUM_MISMATCH", "Soma das duplicatas (" + sum.toPlainString()
                    + ") difere do valor da nota (" + d.totals().invoiceAmount().toPlainString() + ")"));
        if (billing.netAmount() != null && billing.netAmount().compareTo(sum) != 0)
            issues.add(NfeIssue.warning("NFE_BILLING_NET_MISMATCH", "Valor líquido da fatura (" + billing.netAmount().toPlainString()
                    + ") difere da soma das duplicatas (" + sum.toPlainString() + ")"));
    }

    // ------------------------------------------------------------------ utilidades

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean sameNumber(String a, String b) {
        try {
            return Long.parseLong(a) == Long.parseLong(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String padTaxId(String taxId) {
        return taxId.length() >= 14 ? taxId : "0".repeat(14 - taxId.length()) + taxId;
    }
}
