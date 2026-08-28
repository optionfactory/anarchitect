package net.optionfactory.anarchitect.fixtures;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@FacadeStereotype
public class EventPublishingFacade {

    private final ApplicationEventPublisher events;

    public EventPublishingFacade(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publish() {
        events.publishEvent("event");
    }

    @Transactional(propagation = Propagation.NEVER)
    public void neverOnPublish() {
        events.publishEvent("event");
    }
}
