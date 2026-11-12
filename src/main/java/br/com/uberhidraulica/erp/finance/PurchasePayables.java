package br.com.uberhidraulica.erp.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrato público do Financeiro para Compras registrar as obrigações de uma compra (`DR-0022` D4, aprovada:
 * obrigações geradas na confirmação da compra, vinculadas à NF-e e protegidas contra duplicidade).
 *
 * <p>Existe porque Compras não acessa tabela nem repositório do Financeiro. O que atravessa a fronteira é
 * este contrato e ids — nunca entidade de outro módulo.</p>
 *
 * <p>Registrar obrigação <b>não</b> é pagar. Toda conta criada aqui nasce em aberto, sem liquidação, e pagar
 * ou cancelar continua exigindo as permissões do Financeiro. O briefing do proprietário é explícito: XML com
 * cobrança não marca conta como paga.</p>
 */
public interface PurchasePayables {

    /**
     * Uma parcela a registrar.
     *
     * @param number    número da parcela, a partir de 1
     * @param dueDate   vencimento, obrigatório
     * @param amount    valor da parcela, 2 casas
     * @param reference identificação da duplicata na nota, ou nulo quando a parcela foi informada (D11)
     */
    record Installment(int number, LocalDate dueDate, BigDecimal amount, String reference) {}

    /**
     * Pedido de registro.
     *
     * @param description        descrição que aparece no Financeiro
     * @param supplierName       nome do fornecedor como texto, do jeito que o Financeiro guarda hoje
     * @param expenseCategoryId  categoria de despesa, obrigatória e ativa
     * @param notes              observação livre
     * @param installments       parcelas; a soma é conferida por quem chama, antes de chegar aqui
     * @param idempotencyPrefix  prefixo estável do pedido (na importação: a chave de acesso da NF-e). A chave
     *                           de cada conta é {@code prefixo:número}, de modo que reimportar a mesma nota
     *                           não crie conta nova — é a proteção contra duplicar contas a pagar
     */
    record Request(String description, String supplierName, UUID expenseCategoryId, String notes,
                   List<Installment> installments, String idempotencyPrefix) {}

    /**
     * Conta registrada.
     *
     * @param payableId id da conta no Financeiro, que Compras guarda para ligar compra e parcela
     * @param replayed  verdadeiro quando a conta já existia para a mesma chave e nada foi gravado de novo
     */
    record Registered(UUID payableId, int number, LocalDate dueDate, BigDecimal amount, String reference,
                      boolean replayed) {}

    /**
     * Registra uma conta a pagar por parcela, na transação de quem chama.
     *
     * @throws PurchasePayableException com código estável; nunca deixa escapar exceção interna do Financeiro
     */
    List<Registered> register(Request request);

    /**
     * Cancela as contas indicadas, preservando o histórico (`D5`: anular o documento cancela as obrigações da
     * compra sem apagar nada). Conta já paga não é cancelada em silêncio: a falha é reportada.
     */
    void cancel(List<UUID> payableIds, String reason);
}
