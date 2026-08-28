package net.optionfactory.anarchitect.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

@Entity
public class FetchOwner {

    @ManyToOne
    private AnotherEntity implicitEager;

    @ManyToOne(fetch = FetchType.LAZY)
    private AnotherEntity explicitLazy;

    @ManyToOne(fetch = FetchType.EAGER)
    private AnotherEntity explicitEager;

    @OneToOne
    private SampleEntity implicitEagerOneToOne;
}
