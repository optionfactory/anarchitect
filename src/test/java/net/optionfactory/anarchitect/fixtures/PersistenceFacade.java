package net.optionfactory.anarchitect.fixtures;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@FacadeStereotype
public class PersistenceFacade {

    private final SampleRepository repository = new SampleRepository();

    public PersistenceFacade() {
    }

    public void save() {
        repository.save();
    }

    @Transactional(propagation = Propagation.NEVER)
    public void neverOnPersistence() {
        repository.save();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void mandatoryOnPersistence() {
        repository.save();
    }
}
