package net.optionfactory.anarchitect.validation;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.HashSet;
import java.util.Set;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class ValidationRules {

    public static TaggedRule controllersAreNotMetaAnnotatedWithValidated() {
        final var rule = ArchRuleDefinition.classes()
                .that().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .and().areNotAnnotations()
                .should().notBeMetaAnnotatedWith("org.springframework.validation.annotation.Validated")
                .as("@Controllers should not be meta-annotated with @Validated: use spring unified method validation instead")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule noMethodValidationPostProcessorBeans() {
        final var rule = ArchRuleDefinition.methods()
                .that().haveRawReturnType("org.springframework.validation.beanvalidation.MethodValidationPostProcessor")
                .should().notBeAnnotatedWith("org.springframework.context.annotation.Bean")
                .as("MethodValidationPostProcessor @Bean should not be defined: rely on spring unified method validation instead")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule requestBodyIsValid(String rootPackage) {
        final var rule = ArchRuleDefinition
                .methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<>("have @Valid on parameters annotated with @RequestBody") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        for (final var param : method.getParameters()) {
                            final var hasRequestBody = param.isAnnotatedWith("org.springframework.web.bind.annotation.RequestBody");
                            final var hasValid = param.isAnnotatedWith("jakarta.validation.Valid");

                            if (hasRequestBody && !hasValid) {
                                events.add(SimpleConditionEvent.violated(method, "@RequestBody parameter #%d (%s) without @Valid in %s".formatted(param.getIndex() + 1, param.getRawType().getSimpleName(), Names.describe(method, rootPackage))));
                            }
                        }
                    }

                })
                .as("@RequestBody parameters should be annotated with @Valid")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule requestBodyTypesShouldDeclareConstraints(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<JavaMethod>("have @Valid @RequestBody parameters backed by constraint declarations") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        for (final var param : method.getParameters()) {
                            if (!param.isAnnotatedWith("org.springframework.web.bind.annotation.RequestBody") || !param.isAnnotatedWith("jakarta.validation.Valid")) {
                                continue;
                            }
                            if (declaresConstraints(param.getType(), new HashSet<>())) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(method,
                                    "@RequestBody parameter #%d (%s) is @Valid but no constraint is declared in its type graph in %s"
                                            .formatted(param.getIndex() + 1, Names.simpleNameOf(param.getType().toErasure().getName()), Names.describe(method, rootPackage))));
                        }
                    }
                })
                .as("@Valid on @RequestBody types declaring no constraints has no effect: add constraints or drop @Valid")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static boolean declaresConstraints(JavaType type, Set<String> visited) {
        final var typeName = type.getName();
        if (visited.contains(typeName)) {
            return false;
        }
        visited.add(typeName);
        if (type instanceof JavaParameterizedType ptype) {
            for (final var typeArgument : ptype.getActualTypeArguments()) {
                if (declaresConstraints(typeArgument, visited)) {
                    return true;
                }
            }
        }
        for (final var field : type.toErasure().getAllFields()) {
            if (field.getAnnotations().stream().anyMatch(a -> isConstraintAnnotation(a.getRawType().getName()))) {
                return true;
            }
            if (declaresConstraints(field.getType(), visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConstraintAnnotation(String annotationName) {
        return annotationName.startsWith("jakarta.validation.constraints.") || annotationName.startsWith("javax.validation.constraints.");
    }

}
