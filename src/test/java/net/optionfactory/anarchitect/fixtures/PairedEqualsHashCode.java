package net.optionfactory.anarchitect.fixtures;

public class PairedEqualsHashCode {

    @Override
    public boolean equals(Object other) {
        return other instanceof PairedEqualsHashCode;
    }

    @Override
    public int hashCode() {
        return 1;
    }
}
