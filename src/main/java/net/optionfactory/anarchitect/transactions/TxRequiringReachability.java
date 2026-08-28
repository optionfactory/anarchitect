package net.optionfactory.anarchitect.transactions;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TxRequiringReachability {

    private record Reach(boolean repository, boolean eventPublication) {
    }

    private static final Reach NONE = new Reach(false, false);

    private final Map<String, Reach> cache = new ConcurrentHashMap<>();

    public boolean reachesRepository(JavaCodeUnit start) {
        return reach(start).repository();
    }

    public boolean reachesEventPublication(JavaCodeUnit start) {
        return reach(start).eventPublication();
    }

    private Reach reach(JavaCodeUnit start) {
        return cache.computeIfAbsent(start.getDescription(), description -> compute(start));
    }

    private Reach compute(JavaCodeUnit start) {
        boolean repository = false;
        boolean eventPublication = false;
        final Set<String> visited = new HashSet<>();
        final Queue<JavaCodeUnit> queue = new ArrayDeque<>();
        visited.add(start.getDescription());
        queue.add(start);
        while (!queue.isEmpty() && !(repository && eventPublication)) {
            final var current = queue.poll();
            for (final var call : current.getMethodCallsFromSelf()) {
                final var target = call.getTarget().resolveMember().orElse(null);
                if (target == null) {
                    continue;
                }
                repository |= isRepository(target.getOwner());
                eventPublication |= isEventPublication(call.getTarget().getName(), call.getTarget().getOwner());
                if (visited.add(target.getDescription())) {
                    queue.add(target);
                }
            }
        }
        return new Reach(repository, eventPublication);
    }

    private static boolean isRepository(JavaClass owner) {
        if (owner.isMetaAnnotatedWith("org.springframework.stereotype.Repository")) {
            return true;
        }
        if (owner.isAssignableTo("org.springframework.data.repository.Repository")) {
            return true;
        }
        if (!owner.getSimpleName().endsWith("Repository")) {
            return false;
        }
        return owner.getAllSubclasses().stream()
                .anyMatch(impl -> impl.isMetaAnnotatedWith("org.springframework.stereotype.Repository")
                || impl.isAssignableTo("org.springframework.data.repository.Repository"));
    }

    private static boolean isEventPublication(String methodName, JavaClass owner) {
        return methodName.equals("publishEvent") && owner.isAssignableTo("org.springframework.context.ApplicationEventPublisher");
    }

}
