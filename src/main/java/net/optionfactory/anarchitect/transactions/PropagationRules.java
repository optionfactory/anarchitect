package net.optionfactory.anarchitect.transactions;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.Optional;
import java.util.Set;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class PropagationRules {

    private static final Set<String> NO_TRANSACTION_PROPAGATIONS = Set.of("NEVER", "NOT_SUPPORTED", "SUPPORTS");
    private static final Set<String> BROKEN_ENTRY_POINT_PROPAGATIONS = Set.of("MANDATORY", "NESTED");

    public static TaggedRule persistenceFacadesMustNotDisableTransactions(String rootPackage, TxRequiringReachability reachability) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().areDeclaredInClassesThat().areNotInterfaces()
                .and().arePublic()
                .should(new ArchCondition<JavaMethod>("not disable transactions on persistence paths") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (!reachability.reachesRepository(method)) {
                            return;
                        }
                        final var propagation = declaredPropagation(method).orElse("REQUIRED");
                        if (!NO_TRANSACTION_PROPAGATIONS.contains(propagation)) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "%s reaches a repository and declares propagation %s: repository calls would run without a session and without atomicity; use plain @Transactional (REQUIRED)"
                                        .formatted(Names.describe(method, rootPackage), propagation)));
                    }
                })
                .as("persistence facades are transaction roots: facade methods reaching a repository must use plain @Transactional (REQUIRED): every caller is provably non-transactional, so REQUIRED creates the root transaction, while NEVER/NOT_SUPPORTED/SUPPORTS leave repository calls without a session")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule nonPersistenceFacadesMustDeclareNever(String rootPackage, TxRequiringReachability reachability) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().areDeclaredInClassesThat().areNotInterfaces()
                .and().arePublic()
                .should(new ArchCondition<JavaMethod>("declare propagation NEVER when no transaction is required") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (reachability.reachesRepository(method) || reachability.reachesEventPublication(method)) {
                            return;
                        }
                        if ("NEVER".equals(declaredPropagation(method).orElse("REQUIRED"))) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "%s reaches no repository and publishes no transactional event: declare @Transactional(propagation = Propagation.NEVER) so that no transaction is created/joined and accidental wrapping fails fast"
                                        .formatted(Names.describe(method, rootPackage))));
                    }
                })
                .as("facades that neither reach a repository nor publish transactional events should be marked @Transactional(propagation = NEVER): no transaction is created or joined, and accidental wrapping throws")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule facadeMethodsMustNotDeclareMandatoryOrNested(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().areDeclaredInClassesThat().areNotInterfaces()
                .and().arePublic()
                .should(new ArchCondition<JavaMethod>("not declare MANDATORY or NESTED") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var propagation = declaredPropagation(method).orElse("REQUIRED");
                        if (!BROKEN_ENTRY_POINT_PROPAGATIONS.contains(propagation)) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "%s declares propagation %s: facades are transaction roots called from non-transactional controllers, so MANDATORY throws at runtime, and JpaTransactionManager does not support NESTED savepoints"
                                        .formatted(Names.describe(method, rootPackage), propagation)));
                    }
                })
                .as("public facade methods must not declare MANDATORY or NESTED: facades are transaction roots called from non-transactional controllers, so MANDATORY throws at runtime, and JpaTransactionManager does not support NESTED savepoints")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule transactionalEventListenersMustDeclareFallbackExecution(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areAnnotatedWith("org.springframework.transaction.event.TransactionalEventListener")
                .should(new ArchCondition<JavaMethod>("declare fallbackExecution unless waiting for rollbacks") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var annotation = method.getAnnotationOfType("org.springframework.transaction.event.TransactionalEventListener");
                        final var phase = normalize(annotation.tryGetExplicitlyDeclaredProperty("phase").orElse("AFTER_COMMIT"));
                        if (phase.equals("AFTER_ROLLBACK")) {
                            return;
                        }
                        if (Boolean.TRUE.equals(annotation.tryGetExplicitlyDeclaredProperty("fallbackExecution").orElse(Boolean.FALSE))) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "%s listens at phase %s without fallbackExecution = true: events published from non-transactional code (e.g. NEVER-marked facades) are silently dropped"
                                        .formatted(Names.describe(method, rootPackage), phase)));
                    }
                })
                .as("@TransactionalEventListener not in AFTER_ROLLBACK phase should declare fallbackExecution = true: otherwise events published from non-transactional code (e.g. NEVER-marked facades) are silently dropped")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static Optional<String> declaredPropagation(JavaMethod method) {
        return method.tryGetAnnotationOfType("org.springframework.transaction.annotation.Transactional")
                .flatMap(annotation -> annotation.tryGetExplicitlyDeclaredProperty("propagation"))
                .map(PropagationRules::normalize);
    }

    private static String normalize(Object value) {
        final var s = value.toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }

}
