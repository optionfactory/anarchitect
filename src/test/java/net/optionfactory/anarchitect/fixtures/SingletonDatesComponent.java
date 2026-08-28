package net.optionfactory.anarchitect.fixtures;

import java.text.SimpleDateFormat;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
public class SingletonDatesComponent {

    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
}

@Component
@Scope("prototype")
class PrototypeDatesComponent {

    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
}
