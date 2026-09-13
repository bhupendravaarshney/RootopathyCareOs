package com.rootopathy.careos.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.rootopathy.careos.platform.application.JobQueuePort;
import com.rootopathy.careos.platform.application.DurableNotificationPort;
import com.rootopathy.careos.platform.application.QuarantinedDocumentContent;
import com.rootopathy.careos.platform.application.QuarantinedDocumentContentSourcePort;
import com.rootopathy.careos.platform.domain.DurableJobClaim;
import com.rootopathy.careos.platform.domain.DurableNotificationClaim;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

class ArchitectureRulesTest {
    private static final String ROOT_PACKAGE = "com.rootopathy.careos";

    private final com.tngtech.archunit.core.domain.JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT_PACKAGE);

    @Test
    void domainLayerRemainsFrameworkIndependent() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "..api..",
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.servlet..")
                .check(productionClasses);
    }

    @Test
    void applicationLayerDoesNotDependOnDeliveryOrInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..api..", "..infrastructure..")
                .check(productionClasses);
    }

    @Test
    void infrastructureDoesNotDependOnDeliveryLayer() {
        noClasses()
                .that()
                .resideInAPackage("..infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..api..")
                .check(productionClasses);
    }

    @Test
    void topLevelModulesAreFreeOfCycles() {
        slices().matching("com.rootopathy.careos.(*)..").should().beFreeOfCycles().check(productionClasses);
    }

    @Test
    void restControllersLiveInApiPackages() {
        classes()
                .that()
                .areAnnotatedWith(RestController.class)
                .should()
                .resideInAPackage("..api..")
                .check(productionClasses);
    }

    @Test
    void deliveryLayerCannotReachScannerOnlyQuarantineStreams() {
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(QuarantinedDocumentContentSourcePort.class)
                .check(productionClasses);
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(QuarantinedDocumentContent.class)
                .check(productionClasses);
    }

    @Test
    void deliveryLayerCannotClaimOrCompleteBackgroundJobs() {
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(JobQueuePort.class)
                .check(productionClasses);
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(DurableJobClaim.class)
                .check(productionClasses);
    }

    @Test
    void deliveryLayerCannotClaimOrCompleteDurableNotifications() {
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(DurableNotificationPort.class)
                .check(productionClasses);
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(DurableNotificationClaim.class)
                .check(productionClasses);
    }
}
