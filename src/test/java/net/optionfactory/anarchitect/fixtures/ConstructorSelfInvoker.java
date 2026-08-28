package net.optionfactory.anarchitect.fixtures;

import org.springframework.transaction.annotation.Transactional;

public class ConstructorSelfInvoker {

    public ConstructorSelfInvoker() {
        transactionalMethod();
    }

    @Transactional
    public void transactionalMethod() {
    }
}
