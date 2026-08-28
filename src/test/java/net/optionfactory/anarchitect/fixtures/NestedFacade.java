package net.optionfactory.anarchitect.fixtures;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@FacadeStereotype
public class NestedFacade {

    @Transactional(propagation = Propagation.NESTED)
    public void nested() {
    }
}
