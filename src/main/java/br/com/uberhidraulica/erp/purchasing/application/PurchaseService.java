package br.com.uberhidraulica.erp.purchasing.application;

import br.com.uberhidraulica.erp.finance.PurchasePayableException;
import br.com.uberhidraulica.erp.finance.PurchasePayables;
import br.com.uberhidraulica.erp.iam.CurrentUser;
import br.com.uberhidraulica.erp.purchasing.domain.Purchase;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseDocument;
import br.com.uberhidraulica.erp.purchasing.domain.PurchaseEvent;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasePayableLink;
import br.com.uberhidraulica.erp.purchasing.domain.PurchasingException;
import br.com.uberhidraulica.erp.purchasing.domain.SupplierProductLink;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort;
import br.com.uberhidraulica.erp.purchasing.port.PurchaseRepositoryPort.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Consulta, cancelamento de compra e anulação de documento (`DR-0022` D5, aprovada: manter histórico imutável
 * de importação, anulação e estornos).
 *
 * <p>Nada é apagado. Cancelar a compra cancela as contas a pagar dela, preservando o histórico de ambas; e
 * anular o documento libera a chave de acesso para nova importação sem perder a linha anulada — é o que o
 * índice único parcial do banco expressa.</p>
 */
@Service
public class PurchaseService {
    public static final int MAX_PAGE_SIZE = 100;

    private final PurchaseRepositoryPort repository;
    private final PurchasePayables payables;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    public PurchaseService(PurchaseRepositoryPort repository, PurchasePayables payables, CurrentUser currentUser) {
        this(repository, payables, currentUser, Clock.systemUTC());
    }

    PurchaseService(PurchaseRepositoryPort repository, PurchasePayables payables, CurrentUser currentUser,
                    Clock clock) {
        this.repository = repository;
        this.payables = payables;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consulta

    @Transactional(readOnly = true)
    public Purchase get(UUID id) {
        return repository.find(id).orElseThrow(() -> new PurchasingException("PURCHASE_NOT_FOUND",
                "Compra não encontrada"));
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        Purchase purchase = get(id);
        return new Detail(purchase, repository.documents(id), repository.payableLinks(id));
    }

    @Transactional(readOnly = true)
    public Page<PurchaseRepositoryPort.PurchaseSummary> search(PurchaseRepositoryPort.Filter filter, int page,
                                                               int size) {
        return repository.search(filter, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    @Transactional(readOnly = true)
    public Page<PurchaseEvent> events(UUID purchaseId, int page, int size) {
        get(purchaseId);
        return repository.events(purchaseId, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }

    @Transactional(readOnly = true)
    public List<SupplierProductLink> links(UUID supplierId) {
        return repository.links(supplierId);
    }

    /**
     * Remove um vínculo de item aprendido. É correção de aprendizado, não reescrita de histórico: o que já foi
     * comprado continua apontando para o produto usado na época, porque o {@code product_id} está no item da
     * compra. Sem isso, um vínculo errado confirmado uma vez se repetiria para sempre em silêncio (`D6`).
     */
    @Transactional
    public void removeLink(UUID supplierId, String supplierCode) {
        repository.deleteLinkIfUnused(supplierId, supplierCode);
    }

    // ------------------------------------------------------------------ cancelamento

    /**
     * Cancela a compra e, com ela, as contas a pagar geradas.
     *
     * <p>Trava a compra com {@code SELECT … FOR UPDATE} antes de qualquer coisa: sem isso, duas requisições
     * simultâneas cancelariam as mesmas contas duas vezes. Conta já paga faz o Financeiro recusar, e aí a
     * transação inteira volta atrás — melhor do que deixar metade das obrigações canceladas.</p>
     *
     * <p>No `I3` não existe recebimento: a entrada física é o `I4`. Quando ela existir, cancelar passará a
     * exigir que não haja recebimento ativo, porque anular nota com estoque já lançado deixaria saldo sem
     * origem e o custo médio contaminado — é parte da `D5`, não detalhe.</p>
     */
    @Transactional
    public Purchase cancel(UUID id, String reason) {
        String motive = requireReason(reason);
        Purchase purchase = repository.lock(id).orElseThrow(() -> new PurchasingException("PURCHASE_NOT_FOUND",
                "Compra não encontrada"));
        purchase.checkCancellation();

        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);

        List<PurchasePayableLink> links = repository.payableLinks(id);
        if (!links.isEmpty()) {
            try {
                payables.cancel(links.stream().map(PurchasePayableLink::payableId).toList(),
                        "Compra cancelada: " + motive);
            } catch (PurchasePayableException refused) {
                throw new PurchasingException("PURCHASE_PAYABLES_REFUSED",
                        "Financeiro recusou cancelar as contas da compra: " + refused.getMessage());
            }
            repository.insertEvent(PurchaseEvent.of(id, PurchaseEvent.Type.PAYABLES_CANCELLED, actor, now, motive,
                    null));
        }
        repository.cancel(id, now, actor, motive);
        repository.insertEvent(PurchaseEvent.of(id, PurchaseEvent.Type.CANCELLED, actor, now, motive, null));
        return get(id);
    }

    /**
     * Anula o documento fiscal (`D5`). Exige a compra já cancelada: anular a nota de uma compra ativa deixaria
     * a compra sem documento e as obrigações em pé, o que é pior do que não anular.
     *
     * <p>Depois da anulação a chave de acesso volta a poder ser importada, porque o índice único do banco só
     * vale entre documentos não anulados. A linha anulada permanece, com motivo e autor.</p>
     */
    @Transactional
    public PurchaseDocument voidDocument(UUID documentId, String reason) {
        String motive = requireReason(reason);
        PurchaseDocument document = repository.findDocument(documentId)
                .orElseThrow(() -> new PurchasingException("PURCHASE_DOCUMENT_NOT_FOUND",
                        "Documento não encontrado"));
        document.checkVoid();

        Purchase purchase = repository.lock(document.purchaseId())
                .orElseThrow(() -> new PurchasingException("PURCHASE_NOT_FOUND", "Compra não encontrada"));
        if (!purchase.cancelled()) {
            throw new PurchasingException("PURCHASE_NOT_CANCELLED",
                    "Cancele a compra antes de anular o documento fiscal");
        }

        UUID actor = currentUser.requireId();
        Instant now = Instant.now(clock);
        repository.voidDocument(documentId, now, actor, motive);
        repository.insertEvent(PurchaseEvent.of(document.purchaseId(), PurchaseEvent.Type.DOCUMENT_VOIDED, actor,
                now, motive, null));
        return repository.findDocument(documentId).orElseThrow();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new PurchasingException("PURCHASE_REASON_REQUIRED",
                    "Motivo é obrigatório e deve ter no máximo 500 caracteres");
        }
        return reason.trim();
    }

    public record Detail(Purchase purchase, List<PurchaseDocument> documents, List<PurchasePayableLink> payables) {}
}
