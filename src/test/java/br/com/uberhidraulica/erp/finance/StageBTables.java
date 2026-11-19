package br.com.uberhidraulica.erp.finance;

/** Tabelas da etapa B da TASK-0024 que os testes limpam, na ordem que respeita as FKs. */
final class StageBTables {
    private StageBTables() {}

    /** Antes de {@code CommercialFixtures.clean}: referenciam recebimento, pagamento ou ajuste. */
    static final String[] BEFORE_COMMERCIAL = {};

    /** Depois: referenciam só cadastros do Financeiro. */
    static final String[] AFTER_COMMERCIAL = {"finance.payment_method_fee", "finance.payment_method_fee_schedule", "finance.financial_account"};
}
