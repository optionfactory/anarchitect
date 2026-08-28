package net.optionfactory.anarchitect.fixtures;

public class StandardStreamUser {

    public void out(String s) {
        System.out.println(s);
    }

    public void stackTrace(Exception e) {
        e.printStackTrace();
    }
}
