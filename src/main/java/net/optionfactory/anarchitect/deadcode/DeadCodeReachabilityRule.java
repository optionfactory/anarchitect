package net.optionfactory.anarchitect.deadcode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaGenericArrayType;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.Priority;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class DeadCodeReachabilityRule implements ArchRule {

    private static final Set<String> IGNORED_METHOD_NAMES = Set.of("equals", "hashCode", "toString", "<clinit>", "<init>");
    private final String basePackage;
    private final ReachabilityStrategy strategy;
    private String description = "Unused methods should be removed";

    public DeadCodeReachabilityRule(String basePackage, ReachabilityStrategy strategy) {
        this.basePackage = basePackage;
        this.strategy = strategy;
    }

    @Override
    public String toString() {
        return description;
    }
    
    

    @Override
    public EvaluationResult evaluate(JavaClasses classes) {
        final Set<JavaMethod> aliveMethods = computeAliveMethods(classes, basePackage);
        final ConditionEvents events = ConditionEvents.Factory.create();
        for (final JavaClass clazz : classes) {
            if (!clazz.getPackageName().startsWith(basePackage)) {
                continue;
            }
            for (final JavaMethod method : clazz.getMethods()) {
                final String name = method.getName();

                if (IGNORED_METHOD_NAMES.contains(name)) {
                    continue;
                }

                // PROTECTIONS FOR ENUMS: Skip compiler-generated boilerplate methods
                if (clazz.isEnum() && (name.equals("values") || name.equals("valueOf") || name.equals("$values"))) {
                    continue;
                }

                if (!aliveMethods.contains(method)) {
                    events.add(SimpleConditionEvent.violated(method, String.format("Method '%s' is unreachable dead code.", method.getFullName())));
                }
            }
        }
        return new EvaluationResult(this, events, Priority.MEDIUM);
    }

    private Set<JavaMethod> computeAliveMethods(JavaClasses classes, String basePackage) {
        final Set<JavaMethod> alive = new HashSet<>();
        final Queue<JavaMethod> queue = new LinkedList<>();
        final Set<JavaClass> visitedDataClasses = new HashSet<>();
        for (final JavaClass clazz : classes) {
            for (final JavaMethod method : clazz.getMethods()) {
                if (strategy.isSource(method, basePackage)) {
                    activateMethod(method, alive, queue, visitedDataClasses, basePackage);
                }
            }
        }
        while (!queue.isEmpty()) {
            final JavaMethod current = queue.poll();
            for (final JavaMethodCall call : current.getMethodCallsFromSelf()) {
                final java.util.Optional<JavaMethod> targetMethodOpt = call.getTarget().resolveMember();
                if (targetMethodOpt.isPresent()) {
                    final JavaMethod target = targetMethodOpt.get();
                    // Unroll concrete interface implementations and virtual subclass method overrides
                    for (final JavaClass subClass : target.getOwner().getAllSubclasses()) {
                        subClass.getAllMethods().stream().filter(m -> m.getName().equals(target.getName()) && m.getRawParameterTypes().equals(target.getRawParameterTypes())).findFirst().ifPresent(concreteMethod -> {
                            activateMethod(concreteMethod, alive, queue, visitedDataClasses, basePackage);
                        });
                    }
                    activateMethod(target, alive, queue, visitedDataClasses, basePackage);
                }
            }
        }
        return alive;
    }

    private void activateMethod(JavaMethod method, Set<JavaMethod> alive, Queue<JavaMethod> queue, Set<JavaClass> visitedDataClasses, String basePackage) {
        if (alive.add(method)) {
            queue.add(method);
            if (strategy.isSource(method, basePackage)) {
                // Inbound Sources: Whitelist BOTH generic parameter types and generic return types
                for (final JavaType paramType : method.getParameterTypes()) {
                    unwrapAndWhitelist(paramType, alive, queue, visitedDataClasses, basePackage);
                }
                unwrapAndWhitelist(method.getReturnType(), alive, queue, visitedDataClasses, basePackage);
            } else if (strategy.isSink(method, basePackage)) {
                // Outbound Sinks: Whitelist ONLY generic parameter types (Request bodies)
                for (final JavaType paramType : method.getParameterTypes()) {
                    unwrapAndWhitelist(paramType, alive, queue, visitedDataClasses, basePackage);
                }
            }
        }
    }

    private void unwrapAndWhitelist(JavaType type, Set<JavaMethod> alive, Queue<JavaMethod> queue, Set<JavaClass> visited, String basePackage) {
        if (type == null) {
            return;
        }

        whitelistDataContractTree(type.toErasure(), alive, queue, visited, basePackage);

        if (type instanceof JavaParameterizedType) {
            final JavaParameterizedType parameterizedType = (JavaParameterizedType) type;
            for (final JavaType typeArgument : parameterizedType.getActualTypeArguments()) {
                unwrapAndWhitelist(typeArgument, alive, queue, visited, basePackage);
            }
        }
        if (type instanceof JavaGenericArrayType) {
            unwrapAndWhitelist(((JavaGenericArrayType) type).getComponentType(), alive, queue, visited, basePackage);
        }
        if (type instanceof JavaWildcardType) {
            final JavaWildcardType wildcardType = (JavaWildcardType) type;
            for (final JavaType bound : wildcardType.getUpperBounds()) {
                unwrapAndWhitelist(bound, alive, queue, visited, basePackage);
            }
            for (final JavaType bound : wildcardType.getLowerBounds()) {
                unwrapAndWhitelist(bound, alive, queue, visited, basePackage);
            }
        }
    }

    private void whitelistDataContractTree(JavaClass clazz, Set<JavaMethod> alive, Queue<JavaMethod> queue, Set<JavaClass> visited, String basePackage) {
        if (!clazz.getPackageName().startsWith(basePackage) || !visited.add(clazz)) {
            return;
        }

        for (final JavaMethod method : clazz.getMethods()) {
            if (alive.add(method)) {
                queue.add(method);
            }
        }

        for (final JavaClass subClass : clazz.getAllSubclasses()) {
            whitelistDataContractTree(subClass, alive, queue, visited, basePackage);
        }

        for (final JavaField field : clazz.getFields()) {
            unwrapAndWhitelist(field.getType(), alive, queue, visited, basePackage);
        }

        for (final JavaMethod method : clazz.getMethods()) {
            if (IGNORED_METHOD_NAMES.contains(method.getName())) {
                continue;
            }
            unwrapAndWhitelist(method.getReturnType(), alive, queue, visited, basePackage);
            for (final JavaType paramType : method.getParameterTypes()) {
                unwrapAndWhitelist(paramType, alive, queue, visited, basePackage);
            }
        }
    }

    @Override
    public void check(JavaClasses classes) {
        final EvaluationResult result = evaluate(classes);
        if (result.hasViolation()) {
            throw new AssertionError(result.getFailureReport().toString());
        }
    }

    @Override
    public String getDescription() {
        return this.description;
    }

    @Override
    public ArchRule because(String reason) {
        return this;
    }

    @Override
    public ArchRule as(String newDescription) {
        this.description = newDescription;
        return this;
    }

    @Override
    public ArchRule allowEmptyShould(boolean allowEmptyShould) {
        return this;
    }
}
