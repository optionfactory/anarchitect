package net.optionfactory.anarchitect.fixtures;

import jakarta.persistence.Entity;

@Entity
public class SampleEntity {

    private AnotherEntity other;

    public AnotherEntity getOther() {
        return other;
    }
}
