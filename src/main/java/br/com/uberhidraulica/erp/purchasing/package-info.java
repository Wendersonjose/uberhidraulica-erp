/**
 * Compras e Fornecedores (TASK-0021, AG-05): fornecedores, importação de NF-e modelo 55, compra, recebimento físico
 * e vínculo com o Financeiro e o Estoque.
 *
 * <p>Compra, recebimento e pagamento são fatos diferentes: o pedido não movimenta estoque, somente o recebimento
 * físico confirmado o faz (pelo contrato público do Estoque), e as obrigações financeiras nascem no Financeiro
 * (pelo contrato público do Financeiro) sem que a compra jamais marque algo como pago. Este módulo nunca acessa
 * tabela ou repositório de outro módulo.</p>
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Compras e Fornecedores",
        allowedDependencies = {"iam", "productcatalog", "inventory", "finance", "workorder"})
package br.com.uberhidraulica.erp.purchasing;
