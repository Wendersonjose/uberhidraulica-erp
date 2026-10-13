package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Gera NF-e SINTÉTICAS para teste. Não é a nota real de referência: os itens, descrições e protocolo são fictícios;
 * apenas a forma (12 linhas somando 1.943,10 em 3 duplicatas de 647,70) imita o caso descrito na TASK-0021.
 * A chave de acesso é calculada de modo consistente com o conteúdo, a menos que se peça o contrário.
 */
final class NfeFixtures {
    static final String ISSUER = "67757350000178";
    static final String RECIPIENT = "01637003000101";

    record Line(String code, String description, String qty, String unitPrice, String total) {}

    String issuer = ISSUER;
    String recipient = RECIPIENT;
    String model = "55";
    int series = 1;
    long number = 21;
    String issuedAt = "2026-10-09T10:15:00-03:00";
    String environment = "1";
    String direction = "1";
    String status = "100";
    boolean protocol = true;
    boolean signature = true;
    String keyOverride;
    String protocolKeyOverride;
    String invoiceAmount;
    String productsAmountOverride;
    String freightTotal = "0.00";
    String ipiTotal = "0.00";
    List<Line> lines = new ArrayList<>(defaultLines());
    List<String[]> installments = new ArrayList<>(List.of(
            new String[]{"001", "2026-10-23", "647.70"},
            new String[]{"002", "2026-10-30", "647.70"},
            new String[]{"003", "2026-11-06", "647.70"}));

    static NfeFixtures valid() { return new NfeFixtures(); }

    /** 12 linhas que somam exatamente 1943.10. */
    static List<Line> defaultLines() {
        return List.of(
                new Line("S-001", "Item sintetico 01", "2.0000", "150.00", "300.00"),
                new Line("S-002", "Item sintetico 02", "1.0000", "245.90", "245.90"),
                new Line("S-003", "Item sintetico 03", "4.0000", "37.50", "150.00"),
                new Line("S-004", "Item sintetico 04", "10.0000", "12.35", "123.50"),
                new Line("S-005", "Item sintetico 05", "1.0000", "310.00", "310.00"),
                new Line("S-006", "Item sintetico 06", "6.0000", "22.10", "132.60"),
                new Line("S-007", "Item sintetico 07", "2.0000", "89.90", "179.80"),
                new Line("S-008", "Item sintetico 08", "3.0000", "41.00", "123.00"),
                new Line("S-009", "Item sintetico 09", "5.0000", "15.60", "78.00"),
                new Line("S-010", "Item sintetico 10", "1.0000", "120.00", "120.00"),
                new Line("S-011", "Item sintetico 11", "8.0000", "9.75", "78.00"),
                new Line("S-012", "Item sintetico 12", "1.0000", "102.30", "102.30"));
    }

    String accessKey() {
        if (keyOverride != null) return keyOverride;
        LocalDate date = OffsetDateTimeParse.date(issuedAt);
        String base = "31" + String.format("%02d%02d", date.getYear() % 100, date.getMonthValue())
                + pad(issuer, 14) + model + String.format("%03d", series) + String.format("%09d", number) + "1" + "01020215";
        return base + checkDigit(base);
    }

    static String checkDigit(String base43) {
        int sum = 0;
        int weight = 2;
        for (int i = base43.length() - 1; i >= 0; i--) {
            sum += (base43.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int r = sum % 11;
        return String.valueOf(r < 2 ? 0 : 11 - r);
    }

    private static String pad(String id, int size) { return "0".repeat(Math.max(0, size - id.length())) + id; }

    BigDecimal linesTotal() {
        return lines.stream().map(l -> new BigDecimal(l.total())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    String xml() {
        String key = accessKey();
        String products = productsAmountOverride != null ? productsAmountOverride : linesTotal().toPlainString();
        String vnf = invoiceAmount != null ? invoiceAmount : linesTotal().toPlainString();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\"><NFe>");
        sb.append("<infNFe Id=\"NFe").append(key).append("\" versao=\"4.00\">");
        sb.append("<ide><cUF>31</cUF><cNF>").append(key.length() == 44 ? key.substring(35, 43) : "00000000").append("</cNF>")
                .append("<natOp>Venda de mercadoria</natOp><mod>").append(model).append("</mod><serie>").append(series).append("</serie>")
                .append("<nNF>").append(number).append("</nNF><dhEmi>").append(issuedAt).append("</dhEmi><tpNF>").append(direction)
                .append("</tpNF><idDest>1</idDest><tpImp>1</tpImp><tpEmis>1</tpEmis><cDV>")
                .append(key.length() == 44 ? key.substring(43) : "0").append("</cDV><tpAmb>").append(environment)
                .append("</tpAmb><finNFe>1</finNFe></ide>");
        sb.append("<emit><CNPJ>").append(issuer).append("</CNPJ><xNome>FORNECEDOR SINTETICO LTDA</xNome><IE>1234567890123</IE>")
                .append("<enderEmit><xLgr>Rua Teste</xLgr><nro>10</nro><xBairro>Centro</xBairro><xMun>Uberlandia</xMun><UF>MG</UF><CEP>38400000</CEP></enderEmit></emit>");
        sb.append("<dest><CNPJ>").append(recipient).append("</CNPJ><xNome>OFICINA SINTETICA LTDA</xNome></dest>");
        int n = 1;
        for (Line l : lines) {
            sb.append("<det nItem=\"").append(n++).append("\"><prod><cProd>").append(l.code()).append("</cProd><cEAN>SEM GTIN</cEAN><xProd>")
                    .append(l.description()).append("</xProd><NCM>84133090</NCM><CFOP>5102</CFOP><uCom>UN</uCom><qCom>").append(l.qty())
                    .append("</qCom><vUnCom>").append(l.unitPrice()).append("</vUnCom><vProd>").append(l.total())
                    .append("</vProd><cEANTrib>SEM GTIN</cEANTrib><uTrib>UN</uTrib><qTrib>").append(l.qty()).append("</qTrib><vUnTrib>")
                    .append(l.unitPrice()).append("</vUnTrib><indTot>1</indTot></prod><imposto><ICMS><ICMSSN102><orig>0</orig><CSOSN>102</CSOSN>")
                    .append("</ICMSSN102></ICMS></imposto></det>");
        }
        sb.append("<total><ICMSTot><vBC>0.00</vBC><vICMS>0.00</vICMS><vICMSDeson>0.00</vICMSDeson><vFCP>0.00</vFCP><vBCST>0.00</vBCST>")
                .append("<vST>0.00</vST><vFCPST>0.00</vFCPST><vFCPSTRet>0.00</vFCPSTRet><vProd>").append(products).append("</vProd><vFrete>")
                .append(freightTotal).append("</vFrete><vSeg>0.00</vSeg><vDesc>0.00</vDesc><vII>0.00</vII><vIPI>").append(ipiTotal)
                .append("</vIPI><vIPIDevol>0.00</vIPIDevol><vPIS>0.00</vPIS><vCOFINS>0.00</vCOFINS><vOutro>0.00</vOutro><vNF>").append(vnf)
                .append("</vNF></ICMSTot></total>");
        sb.append("<transp><modFrete>0</modFrete></transp>");
        if (!installments.isEmpty()) {
            sb.append("<cobr><fat><nFat>").append(String.format("%06d", number)).append("</nFat><vOrig>").append(vnf)
                    .append("</vOrig><vDesc>0.00</vDesc><vLiq>").append(vnf).append("</vLiq></fat>");
            for (String[] dup : installments)
                sb.append("<dup><nDup>").append(dup[0]).append("</nDup><dVenc>").append(dup[1]).append("</dVenc><vDup>").append(dup[2]).append("</vDup></dup>");
            sb.append("</cobr>");
        }
        sb.append("</infNFe>");
        if (signature) sb.append("<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"><SignedInfo/></Signature>");
        sb.append("</NFe>");
        if (protocol) {
            sb.append("<protNFe versao=\"4.00\"><infProt><tpAmb>").append(environment).append("</tpAmb><verAplic>SINTETICO</verAplic><chNFe>")
                    .append(protocolKeyOverride != null ? protocolKeyOverride : key)
                    .append("</chNFe><dhRecbto>2026-10-09T10:16:00-03:00</dhRecbto><nProt>000000000000001</nProt><digVal>x</digVal><cStat>")
                    .append(status).append("</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo></infProt></protNFe>");
        }
        sb.append("</nfeProc>");
        return sb.toString();
    }

    NfeDocument parse() { return NfeXmlParser.parse(xml()); }

    /** Pequeno apoio para extrair a data de uma string ISO com offset sem depender do parser sob teste. */
    private static final class OffsetDateTimeParse {
        static LocalDate date(String iso) { return java.time.OffsetDateTime.parse(iso).toLocalDate(); }
    }
}
