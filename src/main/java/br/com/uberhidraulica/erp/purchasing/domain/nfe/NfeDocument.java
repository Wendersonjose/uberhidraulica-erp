package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Leitura estruturada de uma NF-e modelo 55 (proc ou NFe avulsa). É apenas o que o XML declara: nada aqui prova
 * autorização, autenticidade ou situação junto à SEFAZ. Campos ausentes no XML permanecem nulos.
 */
public record NfeDocument(
        String accessKeyDeclared,
        String layoutVersion,
        Identification identification,
        Party issuer,
        Party recipient,
        List<Item> items,
        Totals totals,
        Billing billing,
        Transport transport,
        Protocol protocol,
        boolean hasSignature,
        String xmlSha256) {

    public NfeDocument {
        items = List.copyOf(items);
    }

    /** {@code ide}: {@code issuedAt} vem de {@code dhEmi}; documentos antigos só têm {@code dEmi} (sem horário). */
    public record Identification(String stateCode, String naturezaOperacao, String model, String series, Long number,
                                 OffsetDateTime issuedAt, LocalDate issuedOn, String direction, String environment,
                                 String emissionType, String numericCode, String checkDigit, String purpose) {}

    /** Emitente ou destinatário; {@code taxId} é CNPJ ou CPF normalizado (sem máscara). */
    public record Party(String taxId, String name, String tradeName, String stateRegistration, Address address) {}

    public record Address(String street, String number, String district, String city, String state, String zipCode, String phone) {}

    public record Item(int itemNumber, String supplierCode, String gtin, String gtinDeclared, String description, String ncm,
                       String cest, String cfop, String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal grossTotal,
                       BigDecimal freight, BigDecimal insurance, BigDecimal discount, BigDecimal otherExpenses,
                       boolean includedInTotal, String icmsOrigin, String icmsCst, String icmsCsosn, BigDecimal icmsAmount,
                       BigDecimal icmsStAmount, BigDecimal ipiAmount) {}

    public record Totals(BigDecimal icmsBase, BigDecimal icmsAmount, BigDecimal icmsExempted, BigDecimal icmsStBase,
                         BigDecimal icmsStAmount, BigDecimal fcpStAmount, BigDecimal productsAmount, BigDecimal freight,
                         BigDecimal insurance, BigDecimal discount, BigDecimal importTax, BigDecimal ipiAmount,
                         BigDecimal ipiReturned, BigDecimal otherExpenses, BigDecimal invoiceAmount) {}

    public record Billing(String invoiceNumber, BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal netAmount,
                          List<Installment> installments) {
        public Billing {
            installments = List.copyOf(installments);
        }
    }

    public record Installment(String number, LocalDate dueDate, BigDecimal amount) {}

    /** {@code modFrete} 0..9 e transportadora quando informada. */
    public record Transport(String freightMode, Party carrier) {}

    /** {@code protNFe/infProt}: o que o XML afirma sobre a autorização; não substitui a consulta oficial. */
    public record Protocol(String environment, String accessKey, String protocolNumber, OffsetDateTime receivedAt,
                           String statusCode, String statusReason) {}
}
