package br.com.uberhidraulica.erp.purchasing.port;

import br.com.uberhidraulica.erp.purchasing.domain.GoodsReceipt;
import br.com.uberhidraulica.erp.purchasing.domain.ReceivingLine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistência do recebimento físico (`TASK-0021` I4). Recebimento, item e estorno são append-only: não há UPDATE
 * nem DELETE aqui, e o banco também os recusa por trigger.
 */
public interface GoodsReceiptRepositoryPort {

    /** Grava o cabeçalho. A violação de unicidade da chave de idempotência sobe como {@code DuplicateKeyException}. */
    void insert(GoodsReceipt receipt);

    void insertItems(List<GoodsReceipt.Item> items);

    /** Recebimento com itens e estorno, quando houver. */
    Optional<GoodsReceipt> find(UUID id);

    Optional<GoodsReceipt> findByIdempotencyKey(String key);

    /** Recebimentos da compra, do mais recente ao mais antigo, com itens e estorno. */
    List<GoodsReceipt> receiptsOf(UUID purchaseId);

    /**
     * Conferência da compra: pedido, recebido ativo, encerrado e pendente por linha, lidos da visão derivada. Só conta
     * recebimento <b>não estornado</b>. {@code productUnit} vem nulo — a unidade é do catálogo, não de Compras.
     */
    List<ReceivingLine> receiving(UUID purchaseId);

    /** A compra tem ao menos um recebimento não estornado? Trava cancelamento e anulação (`D5`). */
    boolean hasActiveReceipt(UUID purchaseId);

    void insertReversal(GoodsReceipt.Reversal reversal);

    Optional<GoodsReceipt.Reversal> findReversalByKey(String key);
}
