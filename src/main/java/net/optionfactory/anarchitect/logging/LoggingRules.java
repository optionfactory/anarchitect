package net.optionfactory.anarchitect.logging;

import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class LoggingRules {

    public static TaggedRule standardStreamsShouldNotBeUsedForLogging(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not write to standard streams") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        if (codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                            return;
                        }
                        for (final var access : codeUnit.getFieldAccesses()) {
                            final var target = access.getTarget();
                            if (target.getOwner().getName().equals("java.lang.System") && (target.getName().equals("out") || target.getName().equals("err"))) {
                                events.add(SimpleConditionEvent.violated(codeUnit,
                                        "%s %s accesses %s.%s: use a logger in (%s:%d)"
                                                .formatted(Names.subjectOf(codeUnit), Names.describe(codeUnit, rootPackage), target.getOwner().getSimpleName(), target.getName(), Names.sourceFileName(access), access.getLineNumber())));
                            }
                        }
                        for (final var call : codeUnit.getMethodCallsFromSelf()) {
                            final var target = call.getTarget();
                            final var isPrintStackTrace = target.getOwner().isAssignableTo("java.lang.Throwable")
                                    && target.getName().equals("printStackTrace")
                                    && target.getParameterTypes().isEmpty();
                            if (isPrintStackTrace) {
                                events.add(SimpleConditionEvent.violated(codeUnit,
                                        "%s %s calls Throwable.printStackTrace(): use a logger in (%s:%d)"
                                                .formatted(Names.subjectOf(codeUnit), Names.describe(codeUnit, rootPackage), Names.sourceFileName(call), call.getLineNumber())));
                            }
                        }
                    }
                })
                .as("System.out/err and printStackTrace bypass logging: use slf4j")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

}
