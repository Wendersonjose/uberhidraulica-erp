package br.com.uberhidraulica.erp.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contrato público do Estoque para o recebimento físico de uma compra (`TASK-0021` I4, `DR-0022` D3).
 *
 * <p>O Estoque continua dono do saldo e do custo médio: Compras informa <b>o que chegou fisicamente</b> e a que
 * custo de aquisição, e o Estoque aplica a política de custo médio da `DR-0014`. Compras nunca toca tabela nem
 * repositório do Estoque, e o pedido ou a importação da nota <b>não</b> passam por aqui — só o recebimento
 * confirmado.</p>
 *
 * <p>A chave de idempotência é o item do recebimento: pedir duas vezes a entrada do mesmo item devolve a primeira,
 * em vez de lançar saldo em dobro.</p>
 */
public interface PurchaseStockEntry {

    /**
     * @param movementId       movimentação gravada no Estoque
     * @param balanceAfter     saldo do produto depois do movimento
     * @param averageCostAfter custo médio depois do movimento
     * @param replayed         verdadeiro quando o item já tinha entrada e nada foi lançado de novo
     */
    record Entry(UUID movementId, UUID productId, BigDecimal quantity, BigDecimal balanceAfter,
                 BigDecimal averageCostAfter, boolean replayed) {}

    /**
     * Lança a entrada física de um item recebido, na transação de quem chama.
     *
     * @param receiptItemId item do recebimento; chave de idempotência da entrada
     * @param quantity      quantidade fisicamente recebida, já na unidade do catálogo
     * @param unitCost      custo unitário de aquisição, 4 casas; obrigatório, porque custo desconhecido não é zero
     * @throws PurchaseStockException com código estável (produto inativo, quantidade fora da precisão da unidade…)
     */
    Entry registerEntry(UUID receiptItemId, UUID productId, BigDecimal quantity, BigDecimal unitCost, String reason);

    /**
     * Estorna a entrada de um item recebido. Falha quando o saldo atual não cobre a quantidade a estornar (a peça
     * já foi consumida): estornar levaria o saldo abaixo de zero, e a correção passa a ser devolução ao fornecedor.
     *
     * @throws PurchaseStockException {@code INSUFFICIENT_STOCK} quando a peça já foi consumida
     */
    Entry reverseEntry(UUID receiptItemId, String reason);
}
