package net.optionfactory.anarchitect.entities;

import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class EntityRules {

    public static TaggedRule entitiesShouldNotImplementEqualsOrHashCode() {
        final var rule = ArchRuleDefinition.noMethods()
                .that().haveName("equals").and().haveRawParameterTypes(Object.class)
                .or().haveName("hashCode").and().haveRawParameterTypes(new String[0])
                .should().beDeclaredInClassesThat().areMetaAnnotatedWith("jakarta.persistence.Entity")
                .as("Entities should not implement custom equals or hashCode to avoid breaking Hibernate proxy equality and collection state transitions")
                .allowEmptyShould(true);

        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule facadesShouldNotLeakDetachedEntities(String rootPackage) {
        final var rule = ArchRuleDefinition.methods().that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().arePublic()
                .should(notLeakEntityInstances("Method", "leaks", rootPackage))
                .as("public Facade methods should not leak @Entity instances")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule controllersDoNotReturnEntities(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .and().arePublic()
                .should(notLeakEntityInstances("Controller", "returns", rootPackage))
                .as("@Controllers should not return @Entity instances")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule requestParametersShouldNotBindEntities(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<JavaMethod>("not bind @Entity instances from requests") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        for (final var param : method.getParameters()) {
                            final var binds = param.isAnnotatedWith("org.springframework.web.bind.annotation.RequestBody")
                                    || param.isAnnotatedWith("org.springframework.web.bind.annotation.ModelAttribute");
                            if (!binds) {
                                continue;
                            }
                            final Set<String> bound = new LinkedHashSet<>();
                            collectEntityLeaks(param.getType(), bound, new HashSet<>());
                            if (bound.isEmpty()) {
                                continue;
                            }
                            final var examples = bound.stream()
                                    .map(Names::simpleNameOf)
                                    .limit(3)
                                    .collect(Collectors.joining(", "));
                            final var ellipsis = bound.size() > 3 ? ", ..." : "";
                            events.add(SimpleConditionEvent.violated(method,
                                    "@RequestBody/@ModelAttribute parameter #%d (%s) binds %d @Entity type%s (%s%s) in %s: accept a DTO instead to prevent mass assignment"
                                            .formatted(param.getIndex() + 1, Names.simpleNameOf(param.getType().toErasure().getName()), bound.size(), bound.size() == 1 ? "" : "s", examples, ellipsis, Names.describe(method, rootPackage))));
                        }
                    }
                })
                .as("@RequestBody/@ModelAttribute parameters should not bind @Entity instances: client-controlled mass assignment; accept a DTO instead")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule oneToManyFieldsShouldDeclareMappedBy(String rootPackage) {
        final var rule = ArchRuleDefinition.fields()
                .that().areAnnotatedWith("jakarta.persistence.OneToMany")
                .or().areAnnotatedWith("javax.persistence.OneToMany")
                .should(new ArchCondition<JavaField>("declare mappedBy") {

                    @Override
                    public void check(JavaField field, ConditionEvents events) {
                        String mappedBy = null;
                        for (final var name : List.of("jakarta.persistence.OneToMany", "javax.persistence.OneToMany")) {
                            final var annotation = field.tryGetAnnotationOfType(name);
                            if (annotation.isEmpty()) {
                                continue;
                            }
                            final var value = annotation.get().get("mappedBy");
                            mappedBy = value.isEmpty() ? "" : value.get().toString();
                            break;
                        }
                        if (mappedBy != null && !mappedBy.isEmpty()) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(field,
                                "Field %s.%s is a unidirectional @OneToMany (no inverse side): map the inverse side with mappedBy"
                                        .formatted(Names.stripRootPackage(field.getOwner().getName(), rootPackage), field.getName())));
                    }
                })
                .as("unidirectional @OneToMany fields are mapped with a join table and an extra UPDATE per element: possibly a valid design decision")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static final List<String> TO_ONE_ASSOCIATIONS = List.of(
            "jakarta.persistence.ManyToOne",
            "javax.persistence.ManyToOne",
            "jakarta.persistence.OneToOne",
            "javax.persistence.OneToOne"
    );

    public static TaggedRule toOneAssociationsShouldDeclareFetch(String rootPackage) {
        final var rule = ArchRuleDefinition.fields()
                .that().areAnnotatedWith("jakarta.persistence.ManyToOne")
                .or().areAnnotatedWith("javax.persistence.ManyToOne")
                .or().areAnnotatedWith("jakarta.persistence.OneToOne")
                .or().areAnnotatedWith("javax.persistence.OneToOne")
                .should(new ArchCondition<JavaField>("declare an explicit fetch strategy") {

                    @Override
                    public void check(JavaField field, ConditionEvents events) {
                        for (final var name : TO_ONE_ASSOCIATIONS) {
                            final var annotation = field.tryGetAnnotationOfType(name);
                            if (annotation.isEmpty()) {
                                continue;
                            }
                            if (annotation.get().tryGetExplicitlyDeclaredProperty("fetch").isPresent()) {
                                return;
                            }
                            events.add(SimpleConditionEvent.violated(field,
                                    "Field %s.%s is a @%s with implicit EAGER fetch: declare an explicit fetch strategy"
                                            .formatted(Names.stripRootPackage(field.getOwner().getName(), rootPackage), field.getName(), annotation.get().getRawType().getSimpleName())));
                            return;
                        }
                    }
                })
                .as("ToOne associations default to EAGER fetch, causing cartesian products and N+1 selects: declare an explicit fetch (explicit fetch = EAGER is a valid design decision and is accepted); note that entity graphs compose with LAZY (promoting lazy associations to eager per query) but cannot reliably demote statically-EAGER associations, making LAZY plus entity graphs the preferred alternative")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static ArchCondition<JavaMethod> notLeakEntityInstances(String subject, String verb, String rootPackage) {
        return new ArchCondition<JavaMethod>("not %s @Entity instances".formatted(verb)) {

            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                final Set<String> leaked = new LinkedHashSet<>();
                collectEntityLeaks(method.getReturnType(), leaked, new HashSet<>());
                if (leaked.isEmpty()) {
                    return;
                }
                final var examples = leaked.stream()
                        .map(Names::simpleNameOf)
                        .limit(3)
                        .collect(Collectors.joining(", "));
                final var ellipsis = leaked.size() > 3 ? ", ..." : "";
                final var plural = leaked.size() == 1 ? "" : "s";
                events.add(SimpleConditionEvent.violated(method, "%s %s %s %d @Entity type%s (%s%s)".formatted(subject, Names.describe(method, rootPackage), verb, leaked.size(), plural, examples, ellipsis)));
            }
        };
    }

    private static void collectEntityLeaks(JavaType type, Set<String> leaked, Set<String> visited) {
        final var typeName = type.getName();
        if (visited.contains(typeName)) {
            return;
        }
        visited.add(typeName);
        if (isEntityType(type)) {
            leaked.add(typeName);
        }
        if (type instanceof JavaParameterizedType ptype) {
            for (final var typeArgument : ptype.getActualTypeArguments()) {
                collectEntityLeaks(typeArgument, leaked, visited);
            }
        }
        for (final var field : type.toErasure().getAllFields()) {
            collectEntityLeaks(field.getType(), leaked, visited);
        }
    }

    private static boolean isEntityType(JavaType type) {
        final var erased = type.toErasure();
        return erased.isMetaAnnotatedWith("jakarta.persistence.Entity") || erased.isMetaAnnotatedWith("javax.persistence.Entity");
    }

}
