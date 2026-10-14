package br.com.uberhidraulica.erp.purchasing.domain.nfe;

import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Address;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Billing;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Identification;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Installment;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Item;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Party;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Protocol;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Totals;
import br.com.uberhidraulica.erp.purchasing.domain.nfe.NfeDocument.Transport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lê o XML de uma NF-e (nfeProc/procNFe ou NFe avulsa) sem executar nenhuma validação de negócio.
 *
 * <p>Segurança: DOCTYPE e entidades externas são recusados (XXE), o tamanho é limitado e números só são aceitos
 * em notação decimal simples. O resumo SHA-256 é calculado sobre os bytes recebidos, para detectar a mesma chave
 * reenviada com conteúdo diferente.</p>
 */
public final class NfeXmlParser {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

    private NfeXmlParser() {}

    public static NfeDocument parse(byte[] xml) {
        if (xml == null || xml.length == 0) throw new NfeParseException("NFE_XML_EMPTY", "XML vazio");
        if (xml.length > MAX_BYTES) throw new NfeParseException("NFE_XML_TOO_LARGE", "XML excede o tamanho máximo de 2 MB");
        return read(builder -> builder.parse(new ByteArrayInputStream(xml)), sha256(xml));
    }

    public static NfeDocument parse(String xml) {
        if (xml == null || xml.isBlank()) throw new NfeParseException("NFE_XML_EMPTY", "XML vazio");
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new NfeParseException("NFE_XML_TOO_LARGE", "XML excede o tamanho máximo de 2 MB");
        return read(builder -> builder.parse(new InputSource(new StringReader(xml))), sha256(bytes));
    }

    private interface Source {
        Document parse(DocumentBuilder builder) throws Exception;
    }

    private static NfeDocument read(Source source, String hash) {
        Document document;
        try {
            document = source.parse(secureBuilder());
        } catch (Exception e) {
            throw new NfeParseException("NFE_XML_MALFORMED", "XML malformado ou com construções não permitidas (DOCTYPE/entidades)");
        }
        Element root = document.getDocumentElement();
        Element nfe;
        Element protNfe = null;
        if (is(root, "nfeProc")) {
            nfe = child(root, "NFe");
            protNfe = child(root, "protNFe");
        } else {
            nfe = is(root, "NFe") ? root : null;
        }
        if (nfe == null) throw new NfeParseException("NFE_XML_NOT_NFE", "O XML não contém uma NFe (esperado nfeProc ou NFe)");
        Element inf = child(nfe, "infNFe");
        Element ide = inf == null ? null : child(inf, "ide");
        Element emit = inf == null ? null : child(inf, "emit");
        if (inf == null || ide == null || emit == null)
            throw new NfeParseException("NFE_XML_INCOMPLETE", "NFe sem infNFe/ide/emit");

        String declared = inf.getAttribute("Id");
        if (declared.startsWith("NFe")) declared = declared.substring(3);

        List<Item> items = new ArrayList<>();
        for (Element det : children(inf, "det")) items.add(item(det));

        return new NfeDocument(
                declared.isEmpty() ? null : declared,
                inf.getAttribute("versao").isEmpty() ? null : inf.getAttribute("versao"),
                identification(ide),
                party(emit, "enderEmit"),
                child(inf, "dest") == null ? null : party(child(inf, "dest"), "enderDest"),
                items,
                totals(inf),
                billing(inf),
                transport(inf),
                protNfe == null ? null : protocol(protNfe),
                hasSignature(nfe),
                hash);
    }

    // ------------------------------------------------------------------ blocos

    private static Identification identification(Element ide) {
        OffsetDateTime issuedAt = null;
        LocalDate issuedOn = null;
        String dhEmi = text(ide, "dhEmi");
        if (dhEmi != null) {
            try {
                issuedAt = OffsetDateTime.parse(dhEmi);
                issuedOn = issuedAt.toLocalDate();
            } catch (DateTimeParseException e) {
                throw new NfeParseException("NFE_XML_INVALID_DATE", "dhEmi inválida: " + dhEmi);
            }
        } else {
            issuedOn = date(text(ide, "dEmi"), "dEmi");
        }
        String number = text(ide, "nNF");
        return new Identification(text(ide, "cUF"), text(ide, "natOp"), text(ide, "mod"), text(ide, "serie"),
                number == null ? null : integer(number, "nNF"), issuedAt, issuedOn, text(ide, "tpNF"), text(ide, "tpAmb"),
                text(ide, "tpEmis"), text(ide, "cNF"), text(ide, "cDV"), text(ide, "finNFe"));
    }

    private static Party party(Element element, String addressTag) {
        String taxId = text(element, "CNPJ");
        if (taxId == null) taxId = text(element, "CPF");
        if (taxId == null) taxId = text(element, "idEstrangeiro");
        Element addr = child(element, addressTag);
        Address address = addr == null ? null : new Address(text(addr, "xLgr"), text(addr, "nro"), text(addr, "xBairro"),
                text(addr, "xMun"), text(addr, "UF"), text(addr, "CEP"), text(addr, "fone"));
        String name = text(element, "xNome");
        return new Party(BrazilianTaxId.normalize(taxId), name, text(element, "xFant"), text(element, "IE"), address);
    }

    private static Item item(Element det) {
        String nItem = det.getAttribute("nItem");
        int number = nItem.isEmpty() ? 0 : (int) integer(nItem, "nItem");
        Element prod = child(det, "prod");
        if (prod == null) throw new NfeParseException("NFE_XML_INCOMPLETE", "det " + nItem + " sem prod");
        String declaredGtin = text(prod, "cEAN");
        String gtin = declaredGtin != null && Gtin.isValid(declaredGtin) ? declaredGtin : null;
        String indTot = text(prod, "indTot");

        String origin = null, cst = null, csosn = null;
        BigDecimal icms = null, icmsSt = null, ipi = null;
        Element imposto = child(det, "imposto");
        if (imposto != null) {
            Element icmsGroup = child(imposto, "ICMS");
            Element icmsVariant = icmsGroup == null ? null : firstElement(icmsGroup);
            if (icmsVariant != null) {
                origin = text(icmsVariant, "orig");
                cst = text(icmsVariant, "CST");
                csosn = text(icmsVariant, "CSOSN");
                icms = optionalDecimal(icmsVariant, "vICMS");
                icmsSt = optionalDecimal(icmsVariant, "vICMSST");
            }
            Element ipiGroup = child(imposto, "IPI");
            Element ipiTrib = ipiGroup == null ? null : child(ipiGroup, "IPITrib");
            if (ipiTrib != null) ipi = optionalDecimal(ipiTrib, "vIPI");
        }
        return new Item(number, text(prod, "cProd"), gtin, declaredGtin, text(prod, "xProd"), text(prod, "NCM"),
                text(prod, "CEST"), text(prod, "CFOP"), text(prod, "uCom"), optionalDecimal(prod, "qCom"),
                optionalDecimal(prod, "vUnCom"), optionalDecimal(prod, "vProd"), optionalDecimal(prod, "vFrete"),
                optionalDecimal(prod, "vSeg"), optionalDecimal(prod, "vDesc"), optionalDecimal(prod, "vOutro"),
                indTot == null || indTot.equals("1"), origin, cst, csosn, icms, icmsSt, ipi);
    }

    private static Totals totals(Element inf) {
        Element total = child(inf, "total");
        Element t = total == null ? null : child(total, "ICMSTot");
        if (t == null) return null;
        return new Totals(optionalDecimal(t, "vBC"), optionalDecimal(t, "vICMS"), optionalDecimal(t, "vICMSDeson"),
                optionalDecimal(t, "vBCST"), optionalDecimal(t, "vST"), optionalDecimal(t, "vFCPST"),
                optionalDecimal(t, "vProd"), optionalDecimal(t, "vFrete"), optionalDecimal(t, "vSeg"),
                optionalDecimal(t, "vDesc"), optionalDecimal(t, "vII"), optionalDecimal(t, "vIPI"),
                optionalDecimal(t, "vIPIDevol"), optionalDecimal(t, "vOutro"), optionalDecimal(t, "vNF"));
    }

    private static Billing billing(Element inf) {
        Element cobr = child(inf, "cobr");
        if (cobr == null) return null;
        Element fat = child(cobr, "fat");
        List<Installment> installments = new ArrayList<>();
        for (Element dup : children(cobr, "dup"))
            installments.add(new Installment(text(dup, "nDup"), date(text(dup, "dVenc"), "dVenc"), optionalDecimal(dup, "vDup")));
        return new Billing(fat == null ? null : text(fat, "nFat"), fat == null ? null : optionalDecimal(fat, "vOrig"),
                fat == null ? null : optionalDecimal(fat, "vDesc"), fat == null ? null : optionalDecimal(fat, "vLiq"), installments);
    }

    private static Transport transport(Element inf) {
        Element transp = child(inf, "transp");
        if (transp == null) return null;
        Element carrier = child(transp, "transporta");
        Party party = null;
        if (carrier != null) {
            String taxId = text(carrier, "CNPJ");
            if (taxId == null) taxId = text(carrier, "CPF");
            party = new Party(BrazilianTaxId.normalize(taxId), text(carrier, "xNome"), null, text(carrier, "IE"),
                    new Address(text(carrier, "xEnder"), null, null, text(carrier, "xMun"), text(carrier, "UF"), null, null));
        }
        return new Transport(text(transp, "modFrete"), party);
    }

    private static Protocol protocol(Element protNfe) {
        Element info = child(protNfe, "infProt");
        if (info == null) return null;
        String received = text(info, "dhRecbto");
        OffsetDateTime receivedAt = null;
        if (received != null) {
            try {
                receivedAt = OffsetDateTime.parse(received);
            } catch (DateTimeParseException e) {
                throw new NfeParseException("NFE_XML_INVALID_DATE", "dhRecbto inválida: " + received);
            }
        }
        return new Protocol(text(info, "tpAmb"), text(info, "chNFe"), text(info, "nProt"), receivedAt, text(info, "cStat"),
                text(info, "xMotivo"));
    }

    private static boolean hasSignature(Element nfe) {
        for (Node n = nfe.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && "Signature".equals(e.getLocalName()) && DSIG_NS.equals(e.getNamespaceURI())) return true;
        return false;
    }

    // ------------------------------------------------------------------ DOM e números

    private static boolean is(Element element, String name) { return name.equals(element.getLocalName()); }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && is(e, name)) return e;
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && is(e, name)) found.add(e);
        return found;
    }

    private static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e) return e;
        return null;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null) return null;
        String value = e.getTextContent().trim();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal optionalDecimal(Element parent, String name) {
        String value = text(parent, name);
        if (value == null) return null;
        if (!DECIMAL.matcher(value).matches())
            throw new NfeParseException("NFE_XML_INVALID_NUMBER", "Valor numérico inválido em " + name + ": " + value);
        return new BigDecimal(value);
    }

    private static long integer(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new NfeParseException("NFE_XML_INVALID_NUMBER", "Valor inteiro inválido em " + label + ": " + value);
        }
    }

    private static LocalDate date(String value, String label) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new NfeParseException("NFE_XML_INVALID_DATE", label + " inválida: " + value);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ construção segura do parser

    private static DocumentBuilder secureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            return builder;
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível criar um parser XML seguro", e);
        }
    }
}
