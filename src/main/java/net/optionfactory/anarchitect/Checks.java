package net.optionfactory.anarchitect;

import net.optionfactory.anarchitect.cycles.CyclesRules;
import net.optionfactory.anarchitect.crypto.CryptoRules;
import net.optionfactory.anarchitect.deadcode.DeadCodeRules;
import net.optionfactory.anarchitect.deadcode.ReachabilityStrategy;
import net.optionfactory.anarchitect.dependencies.DependencyRules;
import net.optionfactory.anarchitect.determinism.DeterminismRules;
import net.optionfactory.anarchitect.entities.EntityRules;
import net.optionfactory.anarchitect.equality.EqualityRules;
import net.optionfactory.anarchitect.jdk.ObsoleteJdkRules;
import net.optionfactory.anarchitect.jsonb.JsonbRules;
import net.optionfactory.anarchitect.logging.LoggingRules;
import net.optionfactory.anarchitect.transactions.PropagationRules;
import net.optionfactory.anarchitect.transactions.TransactionRules;
import net.optionfactory.anarchitect.transactions.TxRequiringReachability;
import net.optionfactory.anarchitect.validation.ValidationRules;
import net.optionfactory.anarchitect.web.WebRules;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Set;

public class Checks {

    public enum RuleTags {
        RECOMMENDED, ALL;
    }

    public enum ViolationType {
        WARNING,
        FAILURE;
    }

    public record TaggedRule(ArchRule rule, ViolationType violationType, Set<RuleTags> tags) {

        public static TaggedRule of(ArchRule rule, ViolationType violationType, RuleTags... tags) {
            return new TaggedRule(rule, violationType, Set.copyOf(List.of(tags)));
        }
    }

    public static TaggedRule[] makeRules(String ancestorPackage, Set<RuleTags> configuredTags) {
        final var txRequiringReachability = new TxRequiringReachability();
        return List.of(
                ValidationRules.controllersAreNotMetaAnnotatedWithValidated(),
                ValidationRules.noMethodValidationPostProcessorBeans(),
                ValidationRules.requestBodyIsValid(ancestorPackage),
                ValidationRules.requestBodyTypesShouldDeclareConstraints(ancestorPackage),
                WebRules.controllerEndpointsHaveConsistentTrailingSlashes(),
                WebRules.requestMappingShouldDeclareHttpMethod(ancestorPackage),
                WebRules.requestMappingMethodsShouldBePublic(),
                WebRules.controllersShouldNotCallRepositories(ancestorPackage),
                TransactionRules.facadesAreNotInterfaces(),
                TransactionRules.facadesAreTransactional(ancestorPackage),
                TransactionRules.transactionalAnnotatedMethodsArePublic(),
                WebRules.facadesCallsPerControllerMethod(ancestorPackage),
                EntityRules.facadesShouldNotLeakDetachedEntities(ancestorPackage),
                EntityRules.controllersDoNotReturnEntities(ancestorPackage),
                EntityRules.requestParametersShouldNotBindEntities(ancestorPackage),
                EqualityRules.equalsAndHashCodeAreOverriddenInPairs(),
                TransactionRules.facadesDoNotCallFacades(ancestorPackage),
                PropagationRules.persistenceFacadesMustNotDisableTransactions(ancestorPackage, txRequiringReachability),
                PropagationRules.nonPersistenceFacadesMustDeclareNever(ancestorPackage, txRequiringReachability),
                PropagationRules.facadeMethodsMustNotDeclareMandatoryOrNested(ancestorPackage),
                TransactionRules.transactionalMethodsMustNotCallFacades(ancestorPackage),
                PropagationRules.transactionalEventListenersMustDeclareFallbackExecution(ancestorPackage),
                TransactionRules.noSelfInvocationOfProxiedMethods(ancestorPackage),
                TransactionRules.cacheableAndCachePutAreMutuallyExclusive(ancestorPackage),
                EntityRules.entitiesShouldNotImplementEqualsOrHashCode(),
                JsonbRules.valueEquality(),
                EntityRules.oneToManyFieldsShouldDeclareMappedBy(ancestorPackage),
                EntityRules.toOneAssociationsShouldDeclareFetch(ancestorPackage),
                DeterminismRules.nowMethodsWithoutZoneOrClock(ancestorPackage),
                DeterminismRules.noStaticNonThreadSafeDateFormatters(ancestorPackage),
                DeterminismRules.noLegacyDefaultZoneTimeApis(ancestorPackage),
                DeterminismRules.stringCaseConversionsShouldSpecifyLocale(ancestorPackage),
                DeterminismRules.stringOperationsShouldSpecifyCharset(ancestorPackage),
                DeterminismRules.mutableDateFieldsShouldNotLiveInSingletonBeans(ancestorPackage),
                CryptoRules.weakDigestApisAreForbidden(ancestorPackage),
                LoggingRules.standardStreamsShouldNotBeUsedForLogging(ancestorPackage),
                ObsoleteJdkRules.legacyCollectionsShouldNotBeUsed(ancestorPackage),
                DependencyRules.apacheCommonsShouldNotBeUsed(ancestorPackage),
                CyclesRules.noCycles(ancestorPackage),
                DeadCodeRules.noDeadCode(ancestorPackage),
                WebRules.doubleCheckControllerMethodsReturningString()
        ).stream()
                .filter(tr -> tr.tags().stream().anyMatch(t -> configuredTags.contains(t)))
                .toArray(i -> new TaggedRule[i]);
    }

}
