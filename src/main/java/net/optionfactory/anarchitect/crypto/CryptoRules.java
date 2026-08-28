package net.optionfactory.anarchitect.crypto;

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

public class CryptoRules {

    private static final Set<String> LEGACY_PASSWORD_ENCODERS = Set.of(
            "org.springframework.security.crypto.password.MessageDigestPasswordEncoder",
            "org.springframework.security.crypto.password.Md5PasswordEncoder",
            "org.springframework.security.crypto.password.Sha1PasswordEncoder"
    );

    public static TaggedRule weakDigestApisAreForbidden(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not instantiate md5/sha-1 password encoders") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        if (codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                            return;
                        }
                        for (final var call : codeUnit.getConstructorCallsFromSelf()) {
                            final var owner = call.getTarget().getOwner();
                            if (LEGACY_PASSWORD_ENCODERS.contains(owner.getName())) {
                                events.add(SimpleConditionEvent.violated(codeUnit,
                                        "%s %s instantiates weak password encoder %s: use BCrypt/PBKDF2/Argon2 in (%s:%d)"
                                                .formatted(Names.subjectOf(codeUnit), Names.describe(codeUnit, rootPackage), owner.getSimpleName(), Names.sourceFileName(call), call.getLineNumber())));
                            }
                        }
                    }
                })
                .as("MD5/SHA-1 password encoders are broken for security purposes: use BCrypt, PBKDF2 or Argon2")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

}
