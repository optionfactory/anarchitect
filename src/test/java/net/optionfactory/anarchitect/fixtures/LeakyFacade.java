package net.optionfactory.anarchitect.fixtures;

import java.util.List;

@FacadeStereotype
public class LeakyFacade {

    public List<SampleEntity> leak() {
        return List.of();
    }

    public String clean() {
        return "";
    }
}
