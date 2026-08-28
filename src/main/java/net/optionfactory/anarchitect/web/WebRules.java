package net.optionfactory.anarchitect.web;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import net.optionfactory.anarchitect.naming.Names;

public class WebRules {

    public static TaggedRule requestMappingMethodsShouldBePublic() {
        final var rule = ArchRuleDefinition.methods()
                .that().areMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                .should().bePublic()
                .as("@RequestMapping methods should be public: spring dispatches to public handlers")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule controllersShouldNotCallRepositories(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<JavaMethod>("not call repositories directly") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var repositories = method.getMethodCallsFromSelf().stream()
                                .map(call -> call.getTargetOwner())
                                .filter(owner -> owner.isMetaAnnotatedWith("org.springframework.stereotype.Repository"))
                                .map(owner -> owner.getSimpleName())
                                .distinct()
                                .sorted()
                                .toList();
                        if (repositories.isEmpty()) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "Controller %s calls %s: route through a facade to keep the transaction boundary"
                                        .formatted(Names.describe(method, rootPackage), String.join(", ", repositories))));
                    }
                })
                .as("@Controllers should not call @Repository beans directly: route through a facade to keep the transaction boundary")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule requestMappingShouldDeclareHttpMethod(String rootPackage) {
        final var rule = ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<JavaMethod>("declare an http method") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (!method.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")) {
                            return;
                        }
                        final var annotation = method.getAnnotationOfType("org.springframework.web.bind.annotation.RequestMapping");
                        final var httpMethods = annotation.get("method");
                        if (httpMethods.isPresent() && httpMethods.get() instanceof Object[] methods && methods.length > 0) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "@RequestMapping without 'method' accepts every http verb in %s: be explicit (it is also a csrf surface)"
                                        .formatted(Names.describe(method, rootPackage))));
                    }
                })
                .as("@RequestMapping methods should declare an http method: without it the endpoint accepts every verb (also a csrf surface)")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule facadesCallsPerControllerMethod(String rootPackage) {
        final var rule = ArchRuleDefinition
                .methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<>("be calling at most one Facade method") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var facades = method.getMethodCallsFromSelf().stream()
                                .map(call -> call.getTargetOwner())
                                .filter(targetClass -> targetClass.getSimpleName().contains("Facade"))
                                .map(targetClass -> targetClass.getSimpleName())
                                .distinct()
                                .sorted()
                                .toList();

                        if (facades.size() <= 1) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "Controller %s calls %d Facades (%s): orchestration should happen inside a single Facade to maintain transaction boundaries"
                                        .formatted(Names.describe(method, rootPackage), facades.size(), String.join(", ", facades))));
                    }
                })
                .as("@Controllers should call at most one Facade to preserve transaction integrity")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL, RuleTags.RECOMMENDED);
    }

    public static TaggedRule controllerEndpointsHaveConsistentTrailingSlashes() {
        final var rule = ArchRuleDefinition
                .methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<>("have consistent trailing slashes (either all or none end in '/')") {

                    private final List<ConditionEvent> withSlash = new ArrayList<>();
                    private final List<ConditionEvent> withoutSlash = new ArrayList<>();

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (!method.isMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")) {
                            return;
                        }
                        method.getAnnotations().stream()
                                .filter(a -> a.getRawType().getName().endsWith("Mapping"))
                                .flatMap(a -> controllerPaths(a))
                                .filter(v -> v != null)
                                .filter(path -> !path.equals("/") && !path.isEmpty())
                                .filter(path -> !path.endsWith("**"))
                                .filter(path -> !path.startsWith("/actuator"))
                                .forEach(path -> {
                                    final var hasTrailingSlash = path.endsWith("/");
                                    final var coll = hasTrailingSlash ? withSlash : withoutSlash;
                                    coll.add(SimpleConditionEvent.violated(method, "@Controller path with%s trailing slash: '%s' in %s".formatted(hasTrailingSlash ? "" : "out", path, method.getDescription())));
                                });
                    }

                    private Stream<String> controllerPaths(JavaAnnotation<JavaMethod> a) {
                        final var values = (String[]) a.get("value").orElse(new String[0]);
                        final var paths = (String[]) a.get("path").orElse(new String[0]);
                        return Stream.concat(Stream.of(values), Stream.of(paths));
                    }

                    @Override
                    public void finish(ConditionEvents events) {
                        if (withSlash.isEmpty() || withoutSlash.isEmpty()) {
                            return;
                        }
                        final var majority = withoutSlash.size() > withSlash.size() ? withoutSlash : withSlash;
                        final var minority = withoutSlash.size() > withSlash.size() ? withSlash : withoutSlash;
                        events.add(SimpleConditionEvent.violated(null, "%s @Controllers use a different trailing slash convention than:".formatted(majority.size())));
                        minority.forEach(events::add);
                    }
                })
                .as("@Controllers endpoints should consistently either all end with a slash or all NOT end with a slash")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.FAILURE, RuleTags.ALL);
    }

    public static TaggedRule doubleCheckControllerMethodsReturningString() {
        final var rule = ArchRuleDefinition
                .methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.web.bind.annotation.ResponseBody")
                .or().areMetaAnnotatedWith("org.springframework.web.bind.annotation.ResponseBody")
                .should().notHaveRawReturnType(String.class)
                .as("double check @ResponseBody controller methods returning String: they serialize as text/plain or application/json depending on media type negotiation")
                .allowEmptyShould(true);
        return TaggedRule.of(rule, ViolationType.WARNING, RuleTags.ALL);
    }

}
