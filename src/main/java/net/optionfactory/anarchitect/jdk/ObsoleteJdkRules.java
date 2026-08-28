package net.optionfactory.anarchitect.jdk;

import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.Set;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class ObsoleteJdkRules {

    private static final Set<String> LEGACY_COLLECTIONS = Set.of(
            "java.util.Vector",
            "java.util.Hashtable",
            "java.util.Stack"
    );

    public static TaggedRule legacyCollectionsShouldNotBeUsed(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not use legacy synchronized collections") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        if (codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                            return;
                        }
                        for (final var call : codeUnit.getConstructorCallsFromSelf()) {
                            final var owner = call.getTarget().getOwner();
                            if (LEGACY_COLLECTIONS.contains(owner.getName())) {
                                events.add(SimpleConditionEvent.violated(codeUnit,
                                        "%s %s instantiates legacy synchronized collection %s: prefer the java.util collections api (ConcurrentHashMap when shared) in (%s:%d)"
                                                .formatted(Names.subjectOf(codeUnit), Names.describe(codeUnit, rootPackage), owner.getSimpleName(), Names.sourceFileName(call), call.getLineNumber())));
                            }
                        }
                    }
                })
                .as("Vector/Hashtable/Stack are legacy synchronized collections: prefer ArrayList/HashMap/ArrayDeque, ConcurrentHashMap for shared state")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

}
