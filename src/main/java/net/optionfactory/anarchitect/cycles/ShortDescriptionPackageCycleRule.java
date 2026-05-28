package net.optionfactory.anarchitect.cycles;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;

public class ShortDescriptionPackageCycleRule implements ArchRule {

    private final ArchRule delegate;

    private ShortDescriptionPackageCycleRule(ArchRule delegate) {
        this.delegate = delegate;
    }

    public static ArchRule shorten(ArchRule rule) {
        return new ShortDescriptionPackageCycleRule(rule);
    }

    @Override
    public void check(JavaClasses classes) {
        final com.tngtech.archunit.lang.EvaluationResult result = evaluate(classes);
        if (result.hasViolation()) {
            throw new AssertionError(result.getFailureReport().toString());
        }
    }

    @Override
    public EvaluationResult evaluate(JavaClasses classes) {
        final com.tngtech.archunit.lang.EvaluationResult rawResult = delegate.evaluate(classes);
        if (!rawResult.hasViolation()) {
            return rawResult;
        }
        final com.tngtech.archunit.lang.ConditionEvents events = ConditionEvents.Factory.create();
        rawResult.getFailureReport().getDetails().stream().filter(line -> line.startsWith("Cycle detected:")).forEach(cycle -> {
            int cutoffIndex = cycle.indexOf("1.");
            final java.lang.String shortened = cutoffIndex != -1 ? cycle.substring(0, cutoffIndex) : cycle;
            final java.lang.String clean = shortened.replace(" Slice ", " ").replaceAll("[\\r\\n ]+", " ").trim();
            events.add(new SimpleConditionEvent(cycle, false, clean));
        });
        return new EvaluationResult(this, events, rawResult.getPriority());
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public ArchRule because(String reason) {
        return new ShortDescriptionPackageCycleRule(delegate.because(reason));
    }

    @Override
    public ArchRule as(String newDescription) {
        return new ShortDescriptionPackageCycleRule(delegate.as(newDescription));
    }

    @Override
    public ArchRule allowEmptyShould(boolean allowEmptyShould) {
        return new ShortDescriptionPackageCycleRule(delegate.allowEmptyShould(allowEmptyShould));
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

}
