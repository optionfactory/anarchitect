package net.optionfactory.anarchitect.fixtures;

import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import java.util.List;

@Entity
public class OneToManyOwner {

    @OneToMany(mappedBy = "owner")
    private List<SampleEntity> bidirectional;

    @OneToMany
    private List<SampleEntity> unidirectional;
}
