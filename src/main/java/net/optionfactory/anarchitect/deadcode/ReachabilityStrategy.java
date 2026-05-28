package net.optionfactory.anarchitect.deadcode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.stream.Stream;

public interface ReachabilityStrategy {

    boolean isSource(JavaMethod method, String basePackage);

    boolean isSink(JavaMethod method, String basePackage);

    default boolean overridesExternalMethod(JavaMethod method, String basePackage) {
        final JavaClass owner = method.getOwner();
        return Stream.concat(owner.getAllRawSuperclasses().stream(), owner.getAllRawInterfaces().stream())
                .filter(parent -> !parent.getName().equals("java.lang.Object"))
                .filter(parent -> !parent.getPackageName().startsWith(basePackage))
                .anyMatch(parent -> parent.getAllMethods()
                    .stream()
                    .anyMatch(parentMethod -> parentMethod.getName().equals(method.getName()) && parentMethod.getRawParameterTypes().equals(method.getRawParameterTypes()))
                );
    }

}
