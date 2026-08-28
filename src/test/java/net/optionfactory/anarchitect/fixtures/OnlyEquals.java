package net.optionfactory.anarchitect.fixtures;

public class OnlyEquals {

    @Override
    public boolean equals(Object other) {
        return other instanceof OnlyEquals;
    }
}
