package net.optionfactory.anarchitect;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import net.optionfactory.anarchitect.dependencies.DependencyRules;
import net.optionfactory.anarchitect.determinism.DeterminismRules;
import net.optionfactory.anarchitect.entities.EntityRules;
import net.optionfactory.anarchitect.equality.EqualityRules;
import net.optionfactory.anarchitect.jdk.ObsoleteJdkRules;
import net.optionfactory.anarchitect.logging.LoggingRules;
import net.optionfactory.anarchitect.transactions.PropagationRules;
import net.optionfactory.anarchitect.transactions.TransactionRules;
import net.optionfactory.anarchitect.transactions.TxRequiringReachability;
import net.optionfactory.anarchitect.web.WebRules;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ChecksTest {

    private static final String FIXTURES = "net.optionfactory.anarchitect.fixtures";
    private static JavaClasses classes;

    @BeforeAll
    public static void importFixtures() {
        classes = new ClassFileImporter().importPackages(FIXTURES);
    }

    private static List<String> violationsOf(Checks.TaggedRule rule) {
        return rule.rule().evaluate(classes).getFailureReport().getDetails();
    }

    @Test
    public void stereotypeMetaAnnotatedFacadesSatisfyTransactionalRule() {
        final var violations = violationsOf(TransactionRules.facadesAreTransactional(FIXTURES));
        Assertions.assertEquals(2, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("jakarta @Transactional in SampleFacade.jakartaAnnotated()")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("missing @Transactional in NakedFacade.missingTransactional()")), () -> violations.toString());
    }

    @Test
    public void facadeStereotypeAnnotationsAreNotFlaggedAsInterfaces() {
        final var violations = violationsOf(TransactionRules.facadesAreNotInterfaces());
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("AnInterfaceFacade"), () -> violations.toString());
    }

    @Test
    public void entityLeaksAreAggregatedPerMethod() {
        final var violations = violationsOf(EntityRules.facadesShouldNotLeakDetachedEntities(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("Method LeakyFacade.leak() leaks 2 @Entity types (SampleEntity, AnotherEntity)"), () -> violations.toString());
    }

    @Test
    public void oneToManyWithoutMappedByIsReported() {
        final var violations = violationsOf(EntityRules.oneToManyFieldsShouldDeclareMappedBy(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("OneToManyOwner.unidirectional"), () -> violations.toString());
    }

    @Test
    public void implicitEagerToOneAssociationsAreReported() {
        final var violations = violationsOf(EntityRules.toOneAssociationsShouldDeclareFetch(FIXTURES));
        Assertions.assertEquals(2, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("FetchOwner.implicitEager is a @ManyToOne with implicit EAGER fetch")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("FetchOwner.implicitEagerOneToOne is a @OneToOne with implicit EAGER fetch")), () -> violations.toString());
    }

    @Test
    public void sameClassInvocationsOfTransactionalMethodsAreReported() {
        final var violations = violationsOf(TransactionRules.noSelfInvocationOfProxiedMethods(FIXTURES));
        Assertions.assertEquals(2, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("SelfInvoking.caller() self-invokes SelfInvoking.transactionalMethod() (@Transactional)")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("ConstructorSelfInvoker.<init>() self-invokes ConstructorSelfInvoker.transactionalMethod()")), () -> violations.toString());
    }

    @Test
    public void localeOverloadsAndZonedNowAndInstantAreAccepted() {
        Assertions.assertEquals(1, violationsOf(DeterminismRules.stringCaseConversionsShouldSpecifyLocale(FIXTURES)).size());
        Assertions.assertEquals(1, violationsOf(DeterminismRules.nowMethodsWithoutZoneOrClock(FIXTURES)).size());
        Assertions.assertEquals(2, violationsOf(DeterminismRules.noLegacyDefaultZoneTimeApis(FIXTURES)).size());
    }

    @Test
    public void defaultCharsetOperationsAreReported() {
        final var violations = violationsOf(DeterminismRules.stringOperationsShouldSpecifyCharset(FIXTURES));
        Assertions.assertEquals(4, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().filter(v -> v.contains("CharsetUser.decode")).count() == 2, () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("CharsetUser.encode(String)")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("CharsetUser.reader")), () -> violations.toString());
    }

    @Test
    public void requestMappingWithoutHttpMethodIsReported() {
        final var violations = violationsOf(WebRules.requestMappingShouldDeclareHttpMethod(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("RequestMappingUser.allVerbs()"), () -> violations.toString());
    }

    @Test
    public void requestParameterBindingEntitiesIsReported() {
        final var violations = violationsOf(EntityRules.requestParametersShouldNotBindEntities(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("parameter #1 (SampleEntity) binds 2 @Entity types (SampleEntity, AnotherEntity)"), () -> violations.toString());
    }

    @Test
    public void equalsWithoutHashCodeIsReported() {
        final var violations = violationsOf(EqualityRules.equalsAndHashCodeAreOverriddenInPairs());
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("OnlyEquals"), () -> violations.toString());
    }

    @Test
    public void mutableDateFieldsInSingletonBeansAreReported() {
        final var violations = violationsOf(DeterminismRules.mutableDateFieldsShouldNotLiveInSingletonBeans(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("SingletonDatesComponent.format"), () -> violations.toString());
    }

    @Test
    public void standardStreamsAndPrintStackTraceAreReported() {
        final var violations = violationsOf(LoggingRules.standardStreamsShouldNotBeUsedForLogging(FIXTURES));
        Assertions.assertEquals(2, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("accesses System.out")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("printStackTrace")), () -> violations.toString());
    }

    @Test
    public void nonPublicHandlersAreReported() {
        final var violations = violationsOf(WebRules.requestMappingMethodsShouldBePublic());
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("packagePrivateHandler"), () -> violations.toString());
    }

    @Test
    public void cacheableAndCachePutConflictIsReported() {
        final var violations = violationsOf(TransactionRules.cacheableAndCachePutAreMutuallyExclusive(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("CacheConflictService.conflicting"), () -> violations.toString());
    }

    @Test
    public void controllersCallingRepositoriesAreReported() {
        final var violations = violationsOf(WebRules.controllersShouldNotCallRepositories(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("RepositoryCallingController.save() calls SampleRepository"), () -> violations.toString());
    }

    @Test
    public void legacyCollectionsAreReported() {
        final var violations = violationsOf(ObsoleteJdkRules.legacyCollectionsShouldNotBeUsed(FIXTURES));
        Assertions.assertEquals(2, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("LegacyCollectionUser.stack()")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("LegacyCollectionUser.vector()")), () -> violations.toString());
    }

    @Test
    public void apacheCommonsUsageIsReported() {
        final var violations = violationsOf(DependencyRules.apacheCommonsShouldNotBeUsed(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("Class CommonsUser uses org.apache.commons.lang3"), () -> violations.toString());
    }

    @Test
    public void noTxPropagationOnPersistenceFacadesIsReported() {
        final var violations = violationsOf(PropagationRules.persistenceFacadesMustNotDisableTransactions(FIXTURES, new TxRequiringReachability()));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("PersistenceFacade.neverOnPersistence() reaches a repository and declares propagation NEVER"), () -> violations.toString());
    }

    @Test
    public void nonPersistenceFacadesWithoutNeverAreReported() {
        final var violations = violationsOf(PropagationRules.nonPersistenceFacadesMustDeclareNever(FIXTURES, new TxRequiringReachability()));
        Assertions.assertEquals(8, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("NeverFacade.unmarked()")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("NestedFacade.nested()")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().noneMatch(v -> v.contains("PersistenceFacade")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().noneMatch(v -> v.contains("EventPublishingFacade")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().noneMatch(v -> v.contains("TxFacadeCaller")), () -> violations.toString());
    }

    @Test
    public void mandatoryAndNestedOnFacadesAreReported() {
        final var violations = violationsOf(PropagationRules.facadeMethodsMustNotDeclareMandatoryOrNested(FIXTURES));
        Assertions.assertEquals(2, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("PersistenceFacade.mandatoryOnPersistence() declares propagation MANDATORY")), () -> violations.toString());
        Assertions.assertTrue(violations.stream().anyMatch(v -> v.contains("NestedFacade.nested() declares propagation NESTED")), () -> violations.toString());
    }

    @Test
    public void transactionalCodeCallingFacadesIsReported() {
        final var violations = violationsOf(TransactionRules.transactionalMethodsMustNotCallFacades(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("TxFacadeCaller.orchestrate() is @Transactional and calls PersistenceFacade"), () -> violations.toString());
    }

    @Test
    public void transactionalEventListenersWithoutFallbackAreReported() {
        final var violations = violationsOf(PropagationRules.transactionalEventListenersMustDeclareFallbackExecution(FIXTURES));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("withoutFallback"), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("phase AFTER_COMMIT"), () -> violations.toString());
    }
}
