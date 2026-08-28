package net.optionfactory.anarchitect.determinism;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.Set;
import java.util.function.Function;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class DeterminismRules {

    private static final Set<String> WALL_CLOCK_TYPES = Set.of(
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.ZonedDateTime",
            "java.time.OffsetDateTime",
            "java.time.OffsetTime"
    );

    public static TaggedRule nowMethodsWithoutZoneOrClock(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not call no-arg now() on wall-clock types") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        forEachNonSyntheticMethodCall(codeUnit, events, rootPackage, target -> {
                            if (!target.getName().equals("now") || !target.getParameterTypes().isEmpty()) {
                                return "";
                            }
                            if (!WALL_CLOCK_TYPES.contains(target.getOwner().getName())) {
                                return "";
                            }
                            return "calls %s.now() without a ZoneId/Clock".formatted(target.getOwner().getSimpleName());
                        });
                    }
                })
                .as("use the now(ZoneId) or now(Clock) overloads instead of the no-args method: wall-clock types depend on the JVM default timezone")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule noStaticNonThreadSafeDateFormatters(String rootPackage) {
        final var rule = ArchRuleDefinition.fields()
                .that().haveRawType("java.text.SimpleDateFormat")
                .or().haveRawType("java.util.Calendar")
                .or().haveRawType("java.util.GregorianCalendar")
                .and().areStatic()
                .should(new ArchCondition<JavaField>("not be static: shared mutable date types are not thread-safe") {

                    @Override
                    public void check(JavaField field, ConditionEvents events) {
                        events.add(SimpleConditionEvent.violated(field,
                                "Field %s.%s is a static %s and is shared across threads: use immutable java.time types or per-call instantiation"
                                        .formatted(Names.stripRootPackage(field.getOwner().getName(), rootPackage), field.getName(), field.getRawType().getSimpleName())));
                    }
                })
                .as("static SimpleDateFormat/Calendar fields are not thread-safe")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule mutableDateFieldsShouldNotLiveInSingletonBeans(String rootPackage) {
        final var rule = ArchRuleDefinition.fields()
                .that().haveRawType("java.text.SimpleDateFormat")
                .or().haveRawType("java.util.Calendar")
                .or().haveRawType("java.util.GregorianCalendar")
                .and().areNotStatic()
                .should(new ArchCondition<JavaField>("not be mutable state of singleton beans") {

                    @Override
                    public void check(JavaField field, ConditionEvents events) {
                        final var owner = field.getOwner();
                        if (!owner.isMetaAnnotatedWith("org.springframework.stereotype.Component") || hasPrototypeScope(owner)) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(field,
                                "Field %s.%s is a mutable %s in a singleton bean and is shared across requests: java.time types are immutable, instantiate per use otherwise"
                                        .formatted(Names.stripRootPackage(owner.getName(), rootPackage), field.getName(), field.getRawType().getSimpleName())));
                    }
                })
                .as("SimpleDateFormat/Calendar fields in singleton beans are shared across all requests and are not thread-safe: use immutable java.time types")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static boolean hasPrototypeScope(JavaClass owner) {
        final var scope = owner.tryGetAnnotationOfType("org.springframework.context.annotation.Scope");
        if (scope.isEmpty()) {
            return false;
        }
        return "prototype".equals(scope.get().get("value").orElse(null)) || "prototype".equals(scope.get().get("scope").orElse(null));
    }

    public static TaggedRule noLegacyDefaultZoneTimeApis(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not use legacy default-zone java.util time apis") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        if (codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                            return;
                        }
                        for (final var call : codeUnit.getConstructorCallsFromSelf()) {
                            final var target = call.getTarget();
                            if (target.getOwner().getName().equals("java.util.Date") && target.getParameterTypes().isEmpty()) {
                                report(codeUnit, call, events, rootPackage, "instantiates java.util.Date(): prefer java.time types");
                            }
                        }
                        forEachNonSyntheticMethodCall(codeUnit, events, rootPackage, target -> {
                            final var isCalendarGetInstance = target.getOwner().getName().equals("java.util.Calendar")
                                    && target.getName().equals("getInstance")
                                    && target.getParameterTypes().isEmpty();
                            return isCalendarGetInstance ? "calls Calendar.getInstance(): prefer java.time types" : "";
                        });
                    }
                })
                .as("java.util.Date() and Calendar.getInstance() depend on the JVM default timezone and are mutable: use java.time")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule stringCaseConversionsShouldSpecifyLocale(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not call no-arg String.toUpperCase()/String.toLowerCase()") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        forEachNonSyntheticMethodCall(codeUnit, events, rootPackage, target -> {
                            if (!target.getOwner().getName().equals("java.lang.String")) {
                                return "";
                            }
                            final var name = target.getName();
                            if ((!name.equals("toUpperCase") && !name.equals("toLowerCase")) || !target.getParameterTypes().isEmpty()) {
                                return "";
                            }
                            return "calls String.%s() without a Locale".formatted(name);
                        });
                    }
                })
                .as("use the toUpperCase(Locale)/toLowerCase(Locale) overloads: the no-arg ones depend on the JVM default locale (e.g. Turkish i)")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule stringOperationsShouldSpecifyCharset(String rootPackage) {
        final var rule = ArchRuleDefinition.codeUnits()
                .should(new ArchCondition<JavaCodeUnit>("not use the platform default charset") {

                    @Override
                    public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                        if (codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                            return;
                        }
                        for (final var call : codeUnit.getConstructorCallsFromSelf()) {
                            final var target = call.getTarget();
                            final var params = target.getParameterTypes();
                            final var owner = target.getOwner().getName();
                            final var isByteStringConstructor = owner.equals("java.lang.String")
                                    && (params.size() == 1 || params.size() == 3)
                                    && isByteArray(params.get(0));
                            final var isDefaultCharsetStream = (owner.equals("java.io.InputStreamReader") || owner.equals("java.io.OutputStreamWriter"))
                                    && params.size() == 1
                                    && !params.get(0).toErasure().isArray();
                            if (isByteStringConstructor) {
                                report(codeUnit, call, events, rootPackage, "decodes bytes with the platform default charset: pass an explicit Charset (e.g. StandardCharsets.UTF_8)");
                            } else if (isDefaultCharsetStream) {
                                report(codeUnit, call, events, rootPackage, "uses the platform default charset: pass an explicit Charset (e.g. StandardCharsets.UTF_8)");
                            }
                        }
                        forEachNonSyntheticMethodCall(codeUnit, events, rootPackage, target -> {
                            final var isGetBytes = target.getOwner().getName().equals("java.lang.String")
                                    && target.getName().equals("getBytes")
                                    && target.getParameterTypes().isEmpty();
                            return isGetBytes ? "encodes bytes with the platform default charset: pass an explicit Charset (e.g. StandardCharsets.UTF_8)" : "";
                        });
                    }
                })
                .as("new String(byte[]), getBytes(), InputStreamReader/OutputStreamWriter without a Charset use the platform default charset: pass an explicit Charset (e.g. StandardCharsets.UTF_8)")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    private static boolean isByteArray(JavaType type) {
        final var erased = type.toErasure();
        return erased.isArray() && erased.getComponentType().getName().equals("byte");
    }

    private interface CallViolation extends Function<AccessTarget.MethodCallTarget, String> {
    }

    private static void report(JavaCodeUnit codeUnit, JavaCall<?> call, ConditionEvents events, String rootPackage, String message) {
        events.add(SimpleConditionEvent.violated(codeUnit,
                "%s %s %s in (%s:%d)".formatted(Names.subjectOf(codeUnit), Names.describe(codeUnit, rootPackage), message, Names.sourceFileName(call), call.getLineNumber())));
    }

    private static void forEachNonSyntheticMethodCall(JavaCodeUnit codeUnit, ConditionEvents events, String rootPackage, CallViolation violation) {
        if (codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)) {
            return;
        }
        for (final var call : codeUnit.getMethodCallsFromSelf()) {
            final var message = violation.apply(call.getTarget());
            if (message.isEmpty()) {
                continue;
            }
            report(codeUnit, call, events, rootPackage, message);
        }
    }

}
