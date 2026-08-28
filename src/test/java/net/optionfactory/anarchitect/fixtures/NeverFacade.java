package net.optionfactory.anarchitect.fixtures;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@FacadeStereotype
public class NeverFacade {

    @Transactional(propagation = Propagation.NEVER)
    public void marked() {
    }

    public void unmarked() {
    }
}
