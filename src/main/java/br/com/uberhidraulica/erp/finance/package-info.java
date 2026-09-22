/**
 * Financeiro (TASK-0015, DR-0015): recebível da OS, recebimentos, ajustes, contas a pagar e fluxo de caixa.
 *
 * <p>Reage à finalização e ao cancelamento da OS na mesma transação dela; lê a base comercial pelo
 * contrato público do Orçamento e nunca pela tabela. Lançamento é imutável, correção é estorno, e saldos e
 * situação são sempre derivados dos lançamentos.</p>
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Financeiro",
        allowedDependencies = {"workorder", "quote", "crm", "iam"})
package br.com.uberhidraulica.erp.finance;
