package br.com.uberhidraulica.erp.iam;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class IamArchitectureTest {
    @Test
    void controllersAndSecurityAdaptersDoNotAccessRepositoryPortsOrJpaPersistence() {
        var classes = new ClassFileImporter().importPackages("br.com.uberhidraulica.erp.iam");
        noClasses().that().resideInAPackage("..iam.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..iam.port..", "..iam.infrastructure.persistence..")
                .check(classes);
        noClasses().that().resideInAPackage("..iam.infrastructure.security..")
                .and().haveSimpleNameNotEndingWith("Adapter")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..iam.port..", "..iam.infrastructure.persistence..")
                .check(classes);
    }

    @Test
    void springDataPaginationDoesNotLeakOutsidePersistenceAdapter() {
        var classes = new ClassFileImporter().importPackages("br.com.uberhidraulica.erp.iam");
        noClasses().that().resideInAnyPackage("..iam.api..", "..iam.application..", "..iam.domain..", "..iam.port..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.data.domain..")
                .check(classes);
    }
}
