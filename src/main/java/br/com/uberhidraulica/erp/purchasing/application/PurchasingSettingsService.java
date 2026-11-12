package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingSettings;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuração do módulo Compras (`DR-0022` D1 e D9).
 *
 * <p>Alterar parâmetro de custo <b>não</b> recalcula custo já gravado, e isso é intencional: o custo
 * histórico é o que foi efetivamente pago. Mudança de regime tributário gera uma quebra visível na série do
 * custo médio, que é o comportamento contábil correto.</p>
 */
@Service
public class PurchasingSettingsService {
    private final PurchaseRepositoryPort repository;
    private final CurrentUser currentUser;

    public PurchasingSettingsService(PurchaseRepositoryPort repository, CurrentUser currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public PurchasingSettings current() {
        return PurchasingSettings.from(repository.settings());
    }

    /**
     * Grava uma configuração. O valor é normalizado e validado antes (CNPJ sem máscara e completo, booleano
     * textual, método de rateio conhecido); o banco repete a validação por CHECK.
     */
    @Transactional
    public PurchasingSettings change(String key, String value) {
        repository.upsertSetting(key, PurchasingSettings.validate(key, value), currentUser.requireId());
        return current();
    }

    /**
     * Chaves apresentadas na interface, com a marca de que os parâmetros de custo <b>não foram validados pelo
     * contador</b> — condição que o proprietário anexou à aprovação da D1.
     */
    @Transactional(readOnly = true)
    public Map<String, String> describe() {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put(PurchasingSettings.KEY_OWN_TAX_ID,
                "CNPJ completo do estabelecimento autorizado. Sem ele nenhuma NF-e confirma, e a raiz do CNPJ não "
                        + "vale como prova de identidade (DR-0022 D9).");
        notes.put(PurchasingSettings.KEY_INCLUDE_IPI,
                "Somar IPI ao custo de aquisição. Padrão da DR-0022 D1: sim. NÃO VALIDADO PELO CONTADOR.");
        notes.put(PurchasingSettings.KEY_INCLUDE_ICMS_ST,
                "Somar ICMS-ST ao custo de aquisição. Padrão da DR-0022 D1: sim. NÃO VALIDADO PELO CONTADOR.");
        notes.put(PurchasingSettings.KEY_INCLUDE_ICMS_NORMAL,
                "Somar ICMS normal ao custo. Padrão da DR-0022 D1: não, porque é crédito para quem o recupera. "
                        + "Mudar só faz sentido com o regime tributário da oficina. NÃO VALIDADO PELO CONTADOR.");
        notes.put(PurchasingSettings.KEY_FREIGHT_ALLOCATION,
                "Rateio de frete e despesas: VALUE (proporcional ao valor, aprovado) ou QUANTITY.");
        return notes;
    }
}
