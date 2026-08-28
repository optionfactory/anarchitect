package net.optionfactory.anarchitect.jsonb;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Hibernate dirty-checks jsonb attributes by comparing the current value with a
/// deep copy of the loaded one, using equals. Any type in the graph that does not
/// implement value equality is therefore always reported as dirty: every flush of
/// an entity holding such a value issues a spurious UPDATE, bumping @Version and
/// racing concurrent reads into ObjectOptimisticLockingFailureException.
///
/// This condition walks the graph of every @JdbcTypeCode(SqlTypes.JSON) field
/// (element types, fields, sealed-interface permits) and requires every
/// project-defined class in it to be a record, an enum, or to declare both
/// equals(Object) and hashCode().
///
/// Known limitation: generic slots are only checked through their actual type
/// arguments, so an unsafe type nested behind two levels of generics (e.g. a
/// List&lt;T&gt; field inside a generic wrapper used as Wrapper&lt;Unsafe&gt;) is not reached.
public class JsonbValueTypesShouldHaveValueEquality extends ArchCondition<JavaField> {

    private static final String JDBC_TYPE_CODE = "org.hibernate.annotations.JdbcTypeCode";
    // org.hibernate.type.SqlTypes#JSON and #JSON_ARRAY
    private static final Set<Integer> SQL_TYPES_JSON = Set.of(3001, 3018);

    public JsonbValueTypesShouldHaveValueEquality() {
        super("be backed, throughout their value type graph, by types implementing value equality");
    }

    @Override
    public void check(JavaField field, ConditionEvents events) {
        if (isJsonMapped(field)) {
            new Traversal(field, events).check(field.getType());
        }
    }

    private static boolean isJsonMapped(JavaField field) {
        return field.getAnnotations().stream()
                .filter(a -> a.getRawType().getName().equals(JDBC_TYPE_CODE))
                .findFirst()
                .flatMap(a -> a.get("value"))
                .filter(Integer.class::isInstance)
                .map(v -> SQL_TYPES_JSON.contains((Integer) v))
                .orElse(false);
    }

    /// JDK types are trusted wholesale: the few without value equality (e.g.
    /// java.util.Locale, java.lang.StringBuilder) are accepted as a trade-off.
    private static boolean isLeaf(JavaClass clazz) {
        final var name = clazz.getName();
        return clazz.isPrimitive()
                || clazz.isEnum()
                || name.startsWith("java.")
                || name.startsWith("tools.jackson.");
    }

    private static boolean declares(JavaClass clazz, String name, String... rawParameterTypes) {
        final var expected = List.of(rawParameterTypes);
        return clazz.getMethods().stream()
                .anyMatch(m -> m.getName().equals(name)
                && m.getRawParameterTypes().stream().map(JavaClass::getName).toList().equals(expected));
    }

    private static class Traversal {

        private final JavaField field;
        private final ConditionEvents events;
        private final Set<String> visited = new HashSet<>();

        Traversal(JavaField field, ConditionEvents events) {
            this.field = field;
            this.events = events;
        }

        void check(JavaType type) {
            if (type instanceof JavaTypeVariable<?>) {
                return;
            }
            if (type instanceof JavaParameterizedType pt) {
                pt.getActualTypeArguments().forEach(this::check);
                check(pt.toErasure());
                return;
            }
            final var erasure = type.toErasure();
            if (erasure.isArray()) {
                report("array %s cannot be verified: map a parameterized List of value types instead".formatted(erasure.getName()));
                return;
            }
            check(erasure);
        }

        void check(JavaClass clazz) {
            if (isLeaf(clazz) || !visited.add(clazz.getName())) {
                return;
            }
            if (clazz.isInterface()) {
                final var subclasses = clazz.getPermittedSubclasses().orElseGet(clazz::getAllSubclasses);
                if (subclasses.isEmpty()) {
                    report("%s is an interface with no imported implementations and cannot guarantee value equality: use sealed interfaces over records".formatted(clazz.getName()));
                    return;
                }
                subclasses.forEach(this::check);
                return;
            }
            if (!clazz.isRecord() && !(declares(clazz, "equals", "java.lang.Object") && declares(clazz, "hashCode"))) {
                report("%s does not implement value equality: make it a record or declare equals(Object) and hashCode()".formatted(clazz.getName()));
                return;
            }
            clazz.getAllFields().forEach(f -> {
                if (!f.getModifiers().contains(JavaModifier.STATIC) && !f.getName().startsWith("this$")) {
                    check(f.getType());
                }
            });
        }

        private void report(String message) {
            events.add(SimpleConditionEvent.violated(field, "Field <%s> is jsonb-mapped and %s".formatted(field.getFullName(), message)));
        }
    }
}
