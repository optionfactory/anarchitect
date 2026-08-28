package net.optionfactory.anarchitect.fixtures;

import org.springframework.transaction.annotation.Transactional;

public class SelfInvoking {

    @Transactional
    public void transactionalMethod() {
    }

    public void caller() {
        transactionalMethod();
    }
}
