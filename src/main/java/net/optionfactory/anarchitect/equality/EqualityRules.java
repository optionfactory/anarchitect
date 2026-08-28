package net.optionfactory.anarchitect.equality;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;

public class EqualityRules {

    public static TaggedRule equalsAndHashCodeAreOverriddenInPairs() {
        final var rule = ArchRuleDefinition.classes()
                .should(new ArchCondition<JavaClass>("override equals and hashCode in pairs") {

                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        if (clazz.isInterface() || clazz.isEnum() || clazz.isRecord() || clazz.isAnnotation()) {
                            return;
                        }
                        if (clazz.isMetaAnnotatedWith("jakarta.persistence.Entity") || clazz.isMetaAnnotatedWith("javax.persistence.Entity")) {
                            return;
                        }
                        final var hasEquals = overrides(clazz, "equals", "java.lang.Object");
                        final var hasHashCode = overrides(clazz, "hashCode");
                        if (hasEquals && !hasHashCode) {
                            events.add(SimpleConditionEvent.violated(clazz, "Class %s overrides equals but not hashCode: unequal hashCodes break hashed collections".formatted(clazz.getName())));
                        } else if (!hasEquals && hasHashCode) {
                            events.add(SimpleConditionEvent.violated(clazz, "Class %s overrides hashCode but not equals".formatted(clazz.getName())));
                        }
                    }
                })
                .as("equals and hashCode should be overridden in pairs: unpaired overrides break hashed collections (@Entity classes are exempt: hibernate proxies)")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static boolean overrides(JavaClass clazz, String name, String... rawParameterTypes) {
        return clazz.getAllMethods().stream()
                .filter(m -> !m.getOwner().getName().equals("java.lang.Object"))
                .anyMatch(m -> m.getName().equals(name)
                && m.getRawParameterTypes().stream().map(JavaClass::getName).toList().equals(List.of(rawParameterTypes)));
    }

}
