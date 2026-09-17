package br.com.uberhidraulica.erp.workorder.application;

import br.com.uberhidraulica.erp.workorder.domain.WorkOrderException;
import br.com.uberhidraulica.erp.workorder.domain.WorkflowStatus;
import br.com.uberhidraulica.erp.workorder.port.WorkOrderRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Configuração das colunas do Kanban (cartão "Configuração do fluxo e status da OS"). */
@Service
public class WorkflowStatusApplicationService {
    private final WorkOrderRepositoryPort repository;
    private final Clock clock = Clock.systemUTC();

    public WorkflowStatusApplicationService(WorkOrderRepositoryPort repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<StatusUsage> list() {
        return repository.statuses().stream().map(s -> new StatusUsage(s, repository.countOrdersInStatus(s.id()))).toList();
    }

    @Transactional
    public WorkflowStatus create(String name, WorkflowStatus.Stage stage) {
        if (stage == null) throw new WorkOrderException("INVALID_WORK_ORDER", "Etapa é obrigatória");
        int position = repository.statuses().stream().mapToInt(WorkflowStatus::position).max().orElse(0) + 10;
        return uniqueName(() -> repository.saveStatus(WorkflowStatus.create(name, stage, position, clock.instant())));
    }

    @Transactional
    public WorkflowStatus rename(UUID id, String name) {
        WorkflowStatus current = find(id);
        return uniqueName(() -> repository.saveStatus(current.rename(name, clock.instant())));
    }

    @Transactional
    public WorkflowStatus setActive(UUID id, boolean active) {
        return repository.saveStatus(find(id).withActive(active, clock.instant()));
    }

    /** Torna o status padrão da sua etapa; o padrão anterior deixa de ser. */
    @Transactional
    public WorkflowStatus makeDefault(UUID id) {
        WorkflowStatus target = find(id);
        if (!target.active()) throw new WorkOrderException("WORK_ORDER_STATUS_INACTIVE", "Status inativo não pode ser o padrão da etapa");
        if (target.stageDefault()) return target;
        Instant now = clock.instant();
        repository.statuses().stream().filter(s -> s.stage() == target.stage() && s.stageDefault())
                .forEach(previous -> repository.saveStatus(previous.withDefault(false, now)));
        return repository.saveStatus(target.withDefault(true, now));
    }

    /** Reordena as colunas conforme a lista recebida, que deve conter todos os status exatamente uma vez. */
    @Transactional
    public List<WorkflowStatus> reorder(List<UUID> orderedIds) {
        List<WorkflowStatus> all = repository.statuses();
        if (orderedIds == null || orderedIds.size() != all.size() || !new HashSet<>(orderedIds).equals(new HashSet<>(all.stream().map(WorkflowStatus::id).toList())))
            throw new WorkOrderException("INVALID_WORK_ORDER", "A ordenação deve conter todos os status exatamente uma vez");
        Instant now = clock.instant();
        for (int i = 0; i < orderedIds.size(); i++) {
            WorkflowStatus status = find(orderedIds.get(i));
            if (status.position() != (i + 1) * 10) repository.saveStatus(status.moveTo((i + 1) * 10, now));
        }
        return repository.statuses();
    }

    private WorkflowStatus find(UUID id) {
        return repository.findStatus(id).orElseThrow(() -> new WorkOrderException("STATUS_NOT_FOUND", "Status não encontrado"));
    }

    private static WorkflowStatus uniqueName(Supplier<WorkflowStatus> action) {
        try { return action.get(); }
        catch (DataIntegrityViolationException e) { throw new WorkOrderException("STATUS_NAME_ALREADY_EXISTS", "Já existe status com este nome"); }
    }

    public record StatusUsage(WorkflowStatus status, long orderCount) {}
}
