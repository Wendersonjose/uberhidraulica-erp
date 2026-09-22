package br.com.uberhidraulica.erp.finance.application;

import br.com.uberhidraulica.erp.finance.domain.FinanceException;
import br.com.uberhidraulica.erp.finance.domain.Money;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort;
import br.com.uberhidraulica.erp.finance.port.FinanceRepositoryPort.DailyAmount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Fluxo de caixa (DR-0015, F-11), sempre derivado dos lançamentos.
 *
 * <p>Realizado e previsto nunca se misturam. Sem saldo inicial, o resultado é entradas, saídas e variação
 * líquida do período — não um saldo de caixa ou bancário. Sem visão por competência.</p>
 */
@Service
public class CashFlowService {
    public static final long MAX_DAYS = 366;

    private final FinanceRepositoryPort repository;

    public CashFlowService(FinanceRepositoryPort repository) { this.repository = repository; }

    public record Day(LocalDate date, BigDecimal inflows, BigDecimal outflows, BigDecimal net) {}

    public record Block(BigDecimal inflows, BigDecimal outflows, BigDecimal net, List<Day> days) {}

    public record CashFlow(LocalDate from, LocalDate to, UUID categoryId, Block realized, Block forecast) {}

    @Transactional(readOnly = true)
    public CashFlow cashFlow(LocalDate from, LocalDate to, UUID categoryId) {
        if (from == null || to == null) throw new FinanceException("INVALID_PERIOD", "Período inicial e final são obrigatórios");
        if (to.isBefore(from)) throw new FinanceException("INVALID_PERIOD", "Período final anterior ao inicial");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new FinanceException("INVALID_PERIOD", "Período máximo de " + MAX_DAYS + " dias");
        return new CashFlow(from, to, categoryId,
                block(repository.realizedInflows(from, to), repository.realizedOutflows(from, to, categoryId)),
                block(repository.forecastInflows(from, to), repository.forecastOutflows(from, to, categoryId)));
    }

    private static Block block(List<DailyAmount> inflows, List<DailyAmount> outflows) {
        Map<LocalDate, BigDecimal[]> byDay = new TreeMap<>();
        inflows.forEach(day -> byDay.computeIfAbsent(day.date(), key -> zeros())[0] = day.amount());
        outflows.forEach(day -> byDay.computeIfAbsent(day.date(), key -> zeros())[1] = day.amount());
        List<Day> days = byDay.entrySet().stream().map(entry -> new Day(entry.getKey(), scaled(entry.getValue()[0]),
                scaled(entry.getValue()[1]), scaled(entry.getValue()[0].subtract(entry.getValue()[1])))).toList();
        BigDecimal in = Money.sum(inflows.stream().map(day -> scaled(day.amount())).toList());
        BigDecimal out = Money.sum(outflows.stream().map(day -> scaled(day.amount())).toList());
        return new Block(in, out, in.subtract(out), days);
    }

    private static BigDecimal[] zeros() { return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}; }

    private static BigDecimal scaled(BigDecimal value) { return value.setScale(Money.SCALE, java.math.RoundingMode.UNNECESSARY); }
}
