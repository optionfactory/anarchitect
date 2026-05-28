package net.optionfactory.anarchitect;

import net.optionfactory.anarchitect.deadcode.DeadCodeReachabilityRule;
import net.optionfactory.anarchitect.deadcode.ReachabilityStrategy;
import net.optionfactory.anarchitect.cycles.ShortDescriptionPackageCycleRule;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class Checks {

    public static ArchRule[] makeRules(String ancestorPackage) {
        return new ArchRule[]{
            validatedControllers(),
            requestBodyIsValid(),
            controllerEndpointsHaveConsistentTrailingSlashes(),
            facadesAreTransactional(),
            transactionalAnnotatedMethodsArePublic(),
            facadesAreNotInterfaces(),
            facadesCallsPerControllerMethod(),
            facadesShouldNotLeakDetachedEntities(),
            entitiesShouldNotImplementEqualsOrHashCode(),
            localDatesNowWithZoneId(),
            noCycles(ancestorPackage),
            noDeadCode(ancestorPackage)
        };
    }

    public static ArchRule entitiesShouldNotImplementEqualsOrHashCode() {
        return ArchRuleDefinition.noMethods()
                .that().haveName("equals").and().haveRawParameterTypes(Object.class)
                .or().haveName("hashCode").and().haveRawParameterTypes(new String[0])
                .should().beDeclaredInClassesThat().areMetaAnnotatedWith("jakarta.persistence.Entity")
                .as("Entities should not implement custom equals or hashCode to avoid breaking Hibernate proxy equality and collection state transitions")
                .allowEmptyShould(true);
    }

    public static ArchRule validatedControllers() {
        return ArchRuleDefinition.classes()
                .that().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .and().areNotAnnotations()
                .should().beMetaAnnotatedWith("org.springframework.validation.annotation.Validated")
                .as("@Controllers must be annotated with @Validated")
                .allowEmptyShould(true);
    }

    public static ArchRule facadesAreNotInterfaces() {
        return ArchRuleDefinition.classes()
                .that().haveSimpleNameContaining("Facade")
                .should().notBeInterfaces()
                .as("Facades should not be interfaces")
                .allowEmptyShould(true);
    }

    public static ArchRule facadesShouldNotLeakDetachedEntities() {
        return ArchRuleDefinition.methods().that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().arePublic()
                .should(new ArchCondition<JavaMethod>("not leak @Entity instances") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        check(method, method.getReturnType(), events, new HashSet<>());
                    }

                    private void check(JavaMethod method, JavaType type, ConditionEvents events, Set<String> visited) {
                        final var typeName = type.getName();
                        if (visited.contains(typeName)) {
                            return;
                        }
                        visited.add(typeName);
                        if (isEntity(type)) {
                            events.add(SimpleConditionEvent.violated(method, String.format("Method %s leaks an @Entity: %s", method.getFullName(), typeName)));
                        }
                        if (type instanceof JavaParameterizedType ptype) {
                            for (final var typeArgument : ptype.getActualTypeArguments()) {
                                check(method, typeArgument, events, visited);
                            }
                        }
                        for (final var field : type.toErasure().getAllFields()) {
                            final var fieldType = field.getType();
                            if (isEntity(fieldType) || containsGenericEntity(fieldType)) {
                                events.add(SimpleConditionEvent.violated(method, String.format("Method %s returns %s which leaks an @Entity via field: %s", method.getFullName(), type.getName(), field.getName())));
                            }
                            check(method, fieldType, events, visited);
                        }
                    }

                    private boolean isEntity(JavaType type) {
                        final var erased = type.toErasure();
                        return erased.isMetaAnnotatedWith("jakarta.persistence.Entity") || erased.isMetaAnnotatedWith("javax.persistence.Entity");
                    }

                    private boolean containsGenericEntity(JavaType type) {
                        return type instanceof JavaParameterizedType pType ? pType.getActualTypeArguments().stream().anyMatch(this::isEntity) : false;
                    }

                })
                .allowEmptyShould(true);
    }

    public static ArchRule transactionalAnnotatedMethodsArePublic() {
        return ArchRuleDefinition.methods()
                .that().areAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .or().areAnnotatedWith("jakarta.transaction.Transactional")
                .should().bePublic()
                .allowEmptyShould(true);
    }

    public static ArchRule facadesAreTransactional() {
        return ArchRuleDefinition.methods()
                .that().areDeclaredInClassesThat().haveSimpleNameContaining("Facade")
                .and().areDeclaredInClassesThat().areNotInterfaces()
                .and().arePublic()
                .should(new ArchCondition<>("be annotated with @org.springframework.transaction.annotation.Transactional and not @jakarta.transaction.Transactional") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var hasSpring = method.isAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                                || method.getOwner().isAnnotatedWith("org.springframework.transaction.annotation.Transactional");

                        final var hasJakarta = method.isAnnotatedWith("jakarta.transaction.Transactional")
                                || method.getOwner().isAnnotatedWith("jakarta.transaction.Transactional");

                        if (!hasSpring && !hasJakarta) {
                            events.add(SimpleConditionEvent.violated(method, "missing @Transactional annotation in %s".formatted(method.getFullName())));
                        }
                        if (hasJakarta) {
                            events.add(SimpleConditionEvent.violated(method, "jakarata @Transactional annotation in %s".formatted(method.getFullName())));
                        }
                    }

                })
                .allowEmptyShould(true);
    }

    public static ArchRule facadesCallsPerControllerMethod() {
        return ArchRuleDefinition
                .methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<>("be calling at most one Facade method") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        final var callCount = method.getMethodCallsFromSelf().stream()
                                .map(call -> call.getTargetOwner())
                                .filter(targetClass -> targetClass.getSimpleName().contains("Facade"))
                                .distinct()
                                .count();

                        if (callCount <= 1) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(method,
                                "Controller %s calls %d different Facades. Orchestration should happen inside a single Facade to maintain transaction boundaries."
                                        .formatted(method.getFullName(), callCount)));
                    }
                })
                .as("@Controllers should be calling at most one Facade method to preserve transaction integrity")
                .allowEmptyShould(true);
    }

    public static ArchRule requestBodyIsValid() {
        return ArchRuleDefinition
                .methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith("org.springframework.stereotype.Controller")
                .should(new ArchCondition<>("have @Valid on parameters annotated with @RequestBody") {

                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        for (final var param : method.getParameters()) {
                            final var hasRequestBody = param.isAnnotatedWith("org.springframework.web.bind.annotation.RequestBody");
                            final var hasValid = param.isAnnotatedWith("jakarta.validation.Valid");

                            if (hasRequestBody && !hasValid) {
                                String message = String.format("@RequestBody parameter without @Valid in method %s", method.getDescription());
                                events.add(SimpleConditionEvent.violated(method, message));
                            }
                        }
                    }

                })
                .as("@RequestBody parameter should be annotated with @Valid to be validated")
                .allowEmptyShould(true);
    }

    public static ArchRule localDatesNowWithZoneId() {
        return ArchRuleDefinition.noClasses()
                .should().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .as("use LocalDate.now(ZoneId) or LocalDateTime.now(ZoneId) instead of the no args method");
    }

    public static ArchRule noCycles(String rootPackage) {
        final var inner = SlicesRuleDefinition
                .slices().matching("%s.(**)".formatted(rootPackage))
                .should().beFreeOfCycles()
                .allowEmptyShould(true);
        return ShortDescriptionPackageCycleRule.shorten(inner);
    }

    public static ArchRule controllerEndpointsHaveConsistentTrailingSlashes() {
        return ArchRuleDefinition
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
    }


    public static ArchRule noDeadCode(String ancestorPackage) {
        return new DeadCodeReachabilityRule(ancestorPackage, new ReachabilityStrategy() {
            @Override
            public boolean isSource(JavaMethod method, String basePackage) {
                final var owner = method.getOwner();
                return method.getName().equals("main")
                        || method.isMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                        || method.isMetaAnnotatedWith("org.springframework.web.bind.annotation.ExceptionHandler")
                        || method.isMetaAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
                        || owner.isMetaAnnotatedWith("org.springframework.context.annotation.Configuration")
                        || method.isMetaAnnotatedWith("jakarta.ws.rs.HttpMethod")
                        || owner.isAssignableTo("org.keycloak.provider.Provider")
                        || owner.isAssignableTo("org.keycloak.provider.ProviderFactory")
                        || owner.isAssignableTo("org.keycloak.provider.Spi")
                        || owner.isMetaAnnotatedWith("jakarta.xml.bind.annotation.XmlRegistry")
                        || overridesExternalMethod(method, basePackage);
            }

            @Override
            public boolean isSink(JavaMethod method, String basePackage) {
                final var owner = method.getOwner();
                return method.isMetaAnnotatedWith("org.springframework.web.service.annotation.HttpExchange")
                        || owner.isMetaAnnotatedWith("org.springframework.web.service.annotation.HttpExchange")
                        || owner.isMetaAnnotatedWith("org.springframework.stereotype.Repository");
            }
        });
    }


}
