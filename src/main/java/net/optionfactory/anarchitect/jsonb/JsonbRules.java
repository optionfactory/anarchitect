package net.optionfactory.anarchitect.jsonb;

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;

public class JsonbRules {

    public static TaggedRule valueEquality() {
        final var rule = ArchRuleDefinition.fields()
                .that().areAnnotatedWith("org.hibernate.annotations.JdbcTypeCode")
                .should(new JsonbValueTypesShouldHaveValueEquality())
                .as("jsonb-mapped fields should be backed by value types implementing equals: identity equality always dirty-checks, causing spurious UPDATEs and @Version bumps on reads")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }
}
