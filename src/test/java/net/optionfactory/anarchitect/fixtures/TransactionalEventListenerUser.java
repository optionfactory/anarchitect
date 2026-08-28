package net.optionfactory.anarchitect.fixtures;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransactionalEventListenerUser {

    @TransactionalEventListener
    public void withoutFallback(String event) {
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void withFallback(String event) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void rollbackListener(String event) {
    }
}
