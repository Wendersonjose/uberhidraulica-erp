package br.com.uberhidraulica.erp.finance.application;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fechamento diário obrigatório da sessão de caixa conforme AG-06 / DR-0018. */
@Component
@EnableScheduling
class CashSessionScheduler {
    private final CashSessionService cash;

    CashSessionScheduler(CashSessionService cash) {
        this.cash = cash;
    }

    @Scheduled(cron = "0 59 23 * * *", zone = "America/Sao_Paulo")
    void closeAtEndOfDay() {
        cash.autoCloseOpenSession();
    }
}
