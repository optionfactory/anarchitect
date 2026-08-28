package net.optionfactory.anarchitect.transactions;

import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class TransactionRules {


    public static TaggedRule facadesAreNotInterfaces() {
        final var rule = ArchRuleDefinition.classes()
                .that().haveSimpleNameContaining("Facade")
                .and().areNotAnnotations()
                .should().notBeInterfaces()
                .as("Facades should not be interfaces")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule facadesAreTransactional(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().areDeclaredInClassesThat().areNotInterfaces()
                .and().arePublic()
                .should(new ArchCondition<>("be @Transactional (directly or via a stereotype)") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var hasSpring = method.isMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                                || method.getOwner().isMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional");

                        final var hasJakarta = method.isMetaAnnotatedWith("jakarta.transaction.Transactional")
                                || method.getOwner().isMetaAnnotatedWith("jakarta.transaction.Transactional");

                        if (!hasSpring && !hasJakarta) {
                            events.add(SimpleConditionEvent.violated(method, "missing @Transactional in %s".formatted(Names.describe(method, rootPackage))));
                        }
                        if (hasJakarta) {
                            events.add(SimpleConditionEvent.violated(method, "jakarta @Transactional in %s: use the org.springframework one".formatted(Names.describe(method, rootPackage))));
                        }
                    }

                })
                .as("public Facade methods should be @Transactional (org.springframework, not jakarta)")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule transactionalAnnotatedMethodsArePublic() {
        final var rule = ArchRuleDefinition.methods()
                .that().areAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .or().areAnnotatedWith("jakarta.transaction.Transactional")
                .should().bePublic()
                .as("methods that are annotated with @Transactional should be public")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule transactionalMethodsMustNotCallFacades(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .should(new ArchCondition<JavaMethod>("not call facades from transactional code") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (method.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                            return;
                        }
                        if (!isEffectivelyTransactional(method)) {
                            return;
                        }
                        final var facades = method.getMethodCallsFromSelf().stream()
                                .map(call -> call.getTargetOwner())
                                .filter(target -> !target.equals(method.getOwner()))
                                .filter(target -> !method.getOwner().isAssignableTo(target.getName()))
                                .filter(target -> target.getSimpleName().contains("Facade"))
                                .map(target -> target.getSimpleName())
                                .distinct()
                                .sorted()
                                .toList();
                        if (facades.isEmpty()) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "%s is @Transactional and calls %s: the facade would join this transaction instead of rooting its own; orchestrate inside the facade or make the caller non-transactional"
                                        .formatted(Names.describe(method, rootPackage), String.join(", ", facades))));
                    }
                })
                .as("facades are the transaction boundary: transactional code must not call facade methods, as the facade would join the caller's transaction instead of rooting its own")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule facadesDoNotCallFacades(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().areDeclaredInClassesThat().areNotInterfaces()
                .should(new ArchCondition<JavaMethod>("not call other Facades") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var callees = method.getMethodCallsFromSelf().stream()
                                .map(call -> call.getTargetOwner())
                                .filter(target -> !target.equals(method.getOwner()))
                                .filter(target -> !method.getOwner().isAssignableTo(target.getName()))
                                .filter(target -> target.getSimpleName().contains("Facade"))
                                .map(target -> target.getSimpleName())
                                .distinct()
                                .sorted()
                                .toList();
                        if (callees.isEmpty()) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "Facade %s calls %s: orchestrate through repositories and services instead of other facades"
                                        .formatted(Names.describe(method, rootPackage), String.join(", ", callees))));
                    }
                })
                .as("Facades should not call other Facades")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule noSelfInvocationOfProxiedMethods(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not self-invoke methods carrying proxy-enforced annotations") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        if (codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                            return;
                        }
                        final Map<String, JavaMethod> sameClassTargets = new LinkedHashMap<>();
                        for (final var call : codeUnit.getMethodCallsFromSelf()) {
                            final var target = call.getTarget().resolveMember().orElse(null);
                            if (target == null || target.getDescription().equals(codeUnit.getDescription())) {
                                continue;
                            }
                            if (!target.getOwner().equals(codeUnit.getOwner())) {
                                continue;
                            }
                            sameClassTargets.putIfAbsent(target.getDescription(), target);
                        }
                        for (final var target : sameClassTargets.values()) {
                            final var annotations = proxyEnforcedAnnotationsOf(target);
                            if (annotations.isEmpty()) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(codeUnit,
                                    "%s self-invokes %s (%s): the call bypasses the proxy and the annotation is ignored"
                                            .formatted(Names.describe(codeUnit, rootPackage), Names.describe(target, rootPackage), String.join(", ", annotations))));
                        }
                    }
                })
                .as("methods should not self-invoke same-class methods carrying @Transactional/@Async/@Cacheable annotations: self-invocation bypasses the proxy and the annotations are silently ignored")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule cacheableAndCachePutAreMutuallyExclusive(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areAnnotatedWith("org.springframework.cache.annotation.Cacheable")
                .should(new ArchCondition<JavaMethod>("not also declare @CachePut") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (!method.isAnnotatedWith("org.springframework.cache.annotation.CachePut")) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "%s declares both @Cacheable and @CachePut: read-through and always-write cache semantics conflict"
                                        .formatted(Names.describe(method, rootPackage))));
                    }
                })
                .as("@Cacheable and @CachePut should not be combined on the same method: conflicting cache semantics")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static List<String> proxyEnforcedAnnotationsOf(JavaMethod method) {
        final List<String> annotations = new ArrayList<>();
        if (method.isMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                || method.isMetaAnnotatedWith("jakarta.transaction.Transactional")) {
            annotations.add("@Transactional");
        }
        if (method.isMetaAnnotatedWith("org.springframework.scheduling.annotation.Async")) {
            annotations.add("@Async");
        }
        if (method.isMetaAnnotatedWith("org.springframework.cache.annotation.Cacheable")
                || method.isMetaAnnotatedWith("org.springframework.cache.annotation.CachePut")
                || method.isMetaAnnotatedWith("org.springframework.cache.annotation.CacheEvict")) {
             annotations.add("@Cacheable");
         }
         return annotations;
     }

    private static boolean isEffectivelyTransactional(JavaMethod method) {
        return method.isMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                || method.isMetaAnnotatedWith("jakarta.transaction.Transactional")
                || method.getOwner().isMetaAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                || method.getOwner().isMetaAnnotatedWith("jakarta.transaction.Transactional");
    }

}

