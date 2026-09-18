package br.com.uberhidraulica.erp.inventory.port;

import br.com.uberhidraulica.erp.inventory.domain.Stock;
import br.com.uberhidraulica.erp.inventory.domain.StockMovement;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface StockRepositoryPort {
    /** Saldo do produto com a linha bloqueada até o fim da transação; cria a linha zerada se faltar. */
    Stock lockBalance(UUID productId);

    Map<UUID, Stock> balances();

    void saveBalance(Stock stock);

    StockMovement record(StockMovement movement);

    Optional<StockMovement> findMovement(UUID id);

    List<StockMovement> movementsOfProduct(UUID productId, int limit, int offset);

    long countMovementsOfProduct(UUID productId);

    /** Baixas da OS ainda não estornadas, para a devolução no cancelamento. */
    List<StockMovement> pendingWorkOrderExits(UUID workOrderId);

    /**
     * Já existe baixa para este item da OS? É a chave de idempotência da baixa: o mesmo item
     * físico nunca desconta duas vezes, qualquer que seja o caminho que dispare a baixa.
     */
    boolean workOrderExitExists(UUID workOrderItemId);

    String setting(String key);

    void saveSetting(String key, String value);
}
