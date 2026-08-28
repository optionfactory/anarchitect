package net.optionfactory.anarchitect.deadcode;

import com.tngtech.archunit.core.domain.JavaMethod;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;

public class DeadCodeRules {

    public static TaggedRule noDeadCode(String basePackage) {
        final var rule = new DeadCodeReachabilityRule(basePackage, new SpringReachabilityStrategy());
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL);
    }
}
