package io.github.sudoitir.rahdar.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.Message;
import jakarta.persistence.Entity;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.Repository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The boundaries Modulith does not see (design D6, ADR-0069). Access between modules and cycles
 * are {@link ModularityTest}'s job; these rules say which framework and client types a module may
 * touch at all.
 */
class BoundaryRulesTest {

    private static final String ROOT = "io.github.sudoitir.rahdar";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * The SQL tail is a per-request stream: it holds its own emitter and hands the caller's security
     * context to it. The pattern covers its nested classes.
     */
    private static final String SQL_TAIL = Pattern.quote(ROOT + ".feature.sql.web.SqlStreamController") + "(\\$.*)?";

    @Test
    void featuresUseOnlyMethodSecurityFromSpringSecurity() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".feature..")
                .and()
                .resideOutsideOfPackages(
                        ROOT + ".feature.identitylocal..",
                        ROOT + ".feature.identityoidc..",
                        ROOT + ".feature.apitokens..")
                .and()
                .haveNameNotMatching(SQL_TAIL)
                .should()
                .dependOnClassesThat(resideInAPackage("org.springframework.security..")
                        .and(not(resideInAPackage("org.springframework.security.access.prepost.."))))
                .check(CLASSES);
    }

    @Test
    void onlyTheSecurityKernelAndRedirectSignInSeeHttpSecurity() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ROOT + ".kernel.security..", ROOT + ".feature.identityoidc..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.security.config.annotation.web.builders.HttpSecurity")
                .check(CLASSES);
    }

    @Test
    void entitiesAndRepositoriesLiveInAModulesPersistencePackage() {
        classes()
                .that()
                .areAnnotatedWith(Entity.class)
                .or()
                .areAssignableTo(Repository.class)
                .should()
                .resideInAPackage("..internal.persistence")
                .check(CLASSES);
    }

    /** Rahdar reaches every broker through the broker SDK (ADR-0203): no broker's own client appears in it. */
    @Test
    void noBrokerClientAppearsAnywhere() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.jolokia..", "org.apache.activemq..", "jakarta.jms..")
                .check(CLASSES);
    }

    /** The SDK's driver interface is for drivers; Rahdar is a consumer of the SDK. */
    @Test
    void theSdksDriverInterfaceIsNeverUsed() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.github.sudoitir.openbroker.spi..")
                .check(CLASSES);
    }

    /** What only Artemis has is reached through the Artemis module and nowhere else. */
    @Test
    void onlyTheArtemisModuleKnowsTheArtemisDriver() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".system.artemis..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.github.sudoitir.openbroker.artemis..")
                .check(CLASSES);
    }

    /** A driver's own interface (Artemis's management, Kafka's admin client) is reached only by that system's module. */
    @Test
    void onlyASystemModuleUnwrapsTheConnection() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ROOT + ".system.artemis..", ROOT + ".system.kafka..")
                .should()
                .callMethod(ClusterConnection.class, "unwrap", Class.class)
                .check(CLASSES);
    }

    /**
     * The features that reach Artemis's own mechanics through the Artemis module. Broker configuration and
     * setup review exist only for Artemis. Every other feature goes through the broker SDK's operations and
     * Rahdar's system interfaces, and this list only ever shrinks.
     */
    private static final String[] REACH_ARTEMIS = {
        ROOT + ".system.artemis..",
        // The composition root lists the module.
        ROOT + ".app..",
        ROOT + ".feature.brokerconfig..",
        ROOT + ".feature.setupreview..",
    };

    @Test
    void onlyTheListedFeaturesReachTheArtemisModule() {
        noClasses()
                .that()
                .resideOutsideOfPackages(REACH_ARTEMIS)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".system.artemis..")
                .check(CLASSES);
    }

    @Test
    void onlyTheStreamKernelAndTheSqlTailHoldAnEmitter() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".kernel.stream..")
                .and()
                .haveNameNotMatching(SQL_TAIL)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(SseEmitter.class)
                .check(CLASSES);
    }

    @Test
    void schedulingStaysInTheJobsKernelAndTheScrapeTiers() {
        noClasses()
                .that()
                .resideOutsideOfPackages(ROOT + ".kernel.jobs..", ROOT + ".platform.scrape..")
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(Scheduled.class, TaskScheduler.class, SchedulingConfigurer.class)
                .check(CLASSES);
    }

    /**
     * Message content reaches a response only through the content policy (ADR-0075 D2): web and MCP code never reads
     * a raw message's body, properties or identifying headers, so an ungoverned path cannot be written there.
     */
    @Test
    void webAndMcpCodeNeverReadsRawMessageContent() {
        Set<String> content = Set.of(
                "body",
                "bodyPreview",
                "properties",
                "stringProperties",
                "intProperties",
                "longProperties",
                "doubleProperties",
                "booleanProperties",
                "correlationId",
                "groupId",
                "userId",
                "replyTo");
        noClasses()
                .that()
                .resideInAnyPackage(ROOT + "..web..", ROOT + "..mcp..")
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a raw message content accessor") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        String owner = call.getTargetOwner().getFullName();
                        return ((owner.equals(ROOT + ".platform.broker.BrowsedMessage")
                                                || owner.equals(ROOT + ".feature.sql.QueryResult$Row"))
                                        && content.contains(call.getName()))
                                || (owner.equals(Message.class.getName())
                                        && Set.of("body", "properties", "headers")
                                                .contains(call.getName()));
                    }
                })
                .check(CLASSES);
    }

    @Test
    void featuresNeitherReachIntoTheContainerNorEnableFrameworkFeatures() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".feature..")
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(ApplicationContext.class, BeanFactory.class)
                .check(CLASSES);
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".feature..")
                .should()
                .beAnnotatedWith(new DescribedPredicate<JavaAnnotation<?>>("an @Enable annotation") {
                    @Override
                    public boolean test(JavaAnnotation<?> annotation) {
                        return annotation.getRawType().getSimpleName().startsWith("Enable");
                    }
                })
                .check(CLASSES);
    }
}
