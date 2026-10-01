package br.com.uberhidraulica.erp.finance.api;

import br.com.uberhidraulica.erp.finance.application.CashSessionService;
import br.com.uberhidraulica.erp.finance.port.CashSessionRepositoryPort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Consulta da sessão encerrada automaticamente que ainda precisa de conferência física. */
@RestController
@RequestMapping("/api/finance/cash")
class CashPendingCheckController {
    private final CashSessionRepositoryPort repository;
    private final CashSessionService service;

    CashPendingCheckController(CashSessionRepositoryPort repository, CashSessionService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping("/sessions/pending-check")
    @PreAuthorize("@iamAuthorization.hasPermission(authentication, 'FINANCE_VIEW')")
    ResponseEntity<CashSessionController.CashSessionResponse> pendingCheck() {
        return repository.findLatestPendingCheck()
                .map(session -> ResponseEntity.ok(CashSessionController.CashSessionResponse.from(
                        session, service.expectedBalance(session.id()))))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
