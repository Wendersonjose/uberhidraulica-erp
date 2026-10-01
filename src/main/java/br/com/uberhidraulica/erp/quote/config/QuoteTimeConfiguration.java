package br.com.uberhidraulica.erp.quote.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Relógio controlável exigido pela arquitetura aprovada da TASK-0001, seção 44.
 *
 * <p>Validade comercial e expiração dependem do tempo do servidor, e um teste que precise provar
 * expiração não pode esperar sete dias. O bean é condicional para que outro módulo possa promovê-lo
 * a primitiva compartilhada sem conflito.</p>
 */
@Configuration
public class QuoteTimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() { return Clock.systemUTC(); }
}
