package net.optionfactory.anarchitect.fixtures;

@FacadeStereotype
public class TxFacadeCaller {

    public void orchestrate() {
        new PersistenceFacade().save();
    }
}
