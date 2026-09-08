package br.com.uberhidraulica.erp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void verifiesModularStructure() {

        ApplicationModules modules =
                ApplicationModules.of(UberhidraulicaErpApplication.class);

        modules.verify();
    }
}