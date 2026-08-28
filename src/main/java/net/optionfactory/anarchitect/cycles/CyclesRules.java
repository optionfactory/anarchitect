package net.optionfactory.anarchitect.cycles;

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;

public class CyclesRules {

    public static TaggedRule noCycles(String rootPackage) {
        final var inner = SlicesRuleDefinition
                .slices().matching("%s.(**)".formatted(rootPackage))
                .should().beFreeOfCycles()
                .allowEmptyShould(true);
        final var rule = ShortDescriptionPackageCycleRule.shorten(inner);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL);
    }
}
