package net.optionfactory.anarchitect.dependencies;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.stream.Collectors;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class DependencyRules {

    private static final String APACHE_COMMONS_PREFIX = "org.apache.commons.";

    public static TaggedRule apacheCommonsShouldNotBeUsed(String rootPackage) {
        final var rule = ArchRuleDefinition.classes()
                .should(new ArchCondition<JavaClass>("not depend on org.apache.commons") {

                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        final var commonsPackages = clazz.getDirectDependenciesFromSelf().stream()
                                .map(dependency -> dependency.getTargetClass().getName())
                                .filter(name -> name.startsWith(APACHE_COMMONS_PREFIX))
                                .map(DependencyRules::commonsPackageOf)
                                .distinct()
                                .sorted()
                                .collect(Collectors.toList());
                        if (commonsPackages.isEmpty()) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(clazz,
                                "Class %s uses %s: prefer jdk or valid equivalents"
                                        .formatted(Names.stripRootPackage(clazz.getName(), rootPackage), String.join(", ", commonsPackages))));
                    }
                })
                .as("org.apache.commons libraries should not be used directly: a long CVE history (commons-collections deserialization gadgets, commons-text Text4Shell, commons-compress archive DoS) and little value over the modern jdk, so they only enlarge the attack surface; prefer jdk or valid equivalents")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static String commonsPackageOf(String className) {
        final var segments = className.substring(APACHE_COMMONS_PREFIX.length()).split("\\.", 2);
        return APACHE_COMMONS_PREFIX + segments[0];
    }

}
