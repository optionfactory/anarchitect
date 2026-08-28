package org.springframework.transaction.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TransactionalEventListener {

    TransactionPhase phase() default TransactionPhase.AFTER_COMMIT;

    boolean fallbackExecution() default false;
}
