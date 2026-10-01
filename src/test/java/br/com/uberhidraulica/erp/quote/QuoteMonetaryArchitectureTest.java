package br.com.uberhidraulica.erp.quote;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante a parte não negociável da DR-0007: dinheiro e quantidade comercial nunca passam por
 * ponto flutuante.
 *
 * <p>Um teste de valor prova o resultado de um cálculo; este prova que nenhum caminho do módulo
 * <em>pode</em> introduzir {@code double} sem que alguém perceba.</p>
 */
class QuoteMonetaryArchitectureTest {
    private static final Set<String> FLOATING = Set.of(
            "double", "float", "java.lang.Double", "java.lang.Float");

    @Test
    void noFloatingPointTypeExistsAnywhereInTheQuoteModule() {
        JavaClasses classes = new ClassFileImporter().importPackages("br.com.uberhidraulica.erp.quote");
        List<String> offenders = new ArrayList<>();

        for (JavaClass type : classes) {
            type.getFields().stream()
                    .filter(field -> FLOATING.contains(field.getRawType().getName()))
                    .forEach(field -> offenders.add("campo " + field.getFullName()));
            type.getMethods().forEach(method -> {
                if (FLOATING.contains(method.getRawReturnType().getName()))
                    offenders.add("retorno de " + method.getFullName());
                method.getRawParameterTypes().stream()
                        .filter(parameter -> FLOATING.contains(parameter.getName()))
                        .forEach(parameter -> offenders.add("parâmetro de " + method.getFullName()));
            });
            type.getConstructors().forEach(constructor -> constructor.getRawParameterTypes().stream()
                    .filter(parameter -> FLOATING.contains(parameter.getName()))
                    .forEach(parameter -> offenders.add("parâmetro de " + constructor.getFullName())));
        }

        assertThat(offenders).as("tipos de ponto flutuante no módulo de orçamento").isEmpty();
    }

    @Test
    void theOnlyRoundingModeUsedForChargedValuesIsHalfUp() {
        assertThat(br.com.uberhidraulica.erp.quote.domain.QuoteItemRevision.CHARGED_ROUNDING)
                .isEqualTo(RoundingMode.HALF_UP);
        assertThat(br.com.uberhidraulica.erp.quote.domain.QuoteItemRevision.CHARGED_SCALE).isEqualTo(2);
        assertThat(br.com.uberhidraulica.erp.quote.domain.QuoteItemRevision.UNIT_SCALE).isEqualTo(4);
    }
}
