package net.optionfactory.anarchitect.fixtures;

import org.springframework.transaction.annotation.Transactional;

@FacadeStereotype
public class SampleFacade {

    public void coveredByStereotype() {
    }

    @Transactional
    public void coveredByDirectSpringAnnotation() {
    }

    @jakarta.transaction.Transactional
    public void jakartaAnnotated() {
    }
}
