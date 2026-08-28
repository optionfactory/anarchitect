package net.optionfactory.anarchitect.fixtures;

public class CrossClassCaller {

    public void caller(SelfInvoking other) {
        other.transactionalMethod();
    }
}
