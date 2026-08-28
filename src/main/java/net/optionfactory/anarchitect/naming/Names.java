package net.optionfactory.anarchitect.naming;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import java.util.stream.Collectors;

public class Names {

    public static String describe(JavaCodeUnit codeUnit, String rootPackage) {
        final var params = codeUnit.getParameterTypes().stream()
                .map(type -> simpleNameOf(type.toErasure().getName()))
                .collect(Collectors.joining(", "));
        return "%s.%s(%s)".formatted(stripRootPackage(codeUnit.getOwner().getName(), rootPackage), codeUnit.getName(), params);
    }

    public static String subjectOf(JavaCodeUnit codeUnit) {
        return codeUnit instanceof JavaConstructor ? "Constructor" : "Method";
    }

    public static String sourceFileName(JavaAccess<?> access) {
        return access.getOrigin().getSourceCodeLocation().getSourceFileName();
    }

    public static String stripRootPackage(String qualifiedName, String rootPackage) {
        final var prefix = rootPackage.isEmpty() ? "" : rootPackage + ".";
        return qualifiedName.startsWith(prefix) ? qualifiedName.substring(prefix.length()) : qualifiedName;
    }

    public static String simpleNameOf(String qualifiedName) {
        var name = qualifiedName;
        var suffix = "";
        if (name.startsWith("[L") && name.endsWith(";")) {
            name = name.substring(2, name.length() - 1);
            suffix = "[]";
        }
        return name.substring(name.lastIndexOf('.') + 1) + suffix;
    }

}
