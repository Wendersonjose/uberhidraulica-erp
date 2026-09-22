package br.com.uberhidraulica.erp.finance;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** DR-0007 no Financeiro: nenhum caminho do módulo pode introduzir ponto flutuante em dinheiro. */
class FinanceMonetaryArchitectureTest {
    private static final Set<String> FLOATING = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    @Test
    void noFloatingPointTypeExistsAnywhereInTheFinanceModule() {
        List<String> offenders = new ArrayList<>();
        for (JavaClass type : new ClassFileImporter().importPackages("br.com.uberhidraulica.erp.finance")) {
            type.getFields().stream().filter(field -> FLOATING.contains(field.getRawType().getName()))
                    .forEach(field -> offenders.add("campo " + field.getFullName()));
            type.getMethods().forEach(method -> {
                if (FLOATING.contains(method.getRawReturnType().getName())) offenders.add("retorno de " + method.getFullName());
                method.getRawParameterTypes().stream().filter(parameter -> FLOATING.contains(parameter.getName()))
                        .forEach(parameter -> offenders.add("parâmetro de " + method.getFullName()));
            });
            type.getConstructors().forEach(constructor -> constructor.getRawParameterTypes().stream()
                    .filter(parameter -> FLOATING.contains(parameter.getName()))
                    .forEach(parameter -> offenders.add("parâmetro de " + constructor.getFullName())));
        }
        assertThat(offenders).as("tipos de ponto flutuante no Financeiro").isEmpty();
    }
}
