package net.optionfactory.anarchitect.fixtures;

import java.util.Calendar;
import java.util.Date;

public class LegacyTimeUser {

    public Date date() {
        return new Date();
    }

    public Calendar calendar() {
        return Calendar.getInstance();
    }
}
