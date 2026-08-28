package net.optionfactory.anarchitect.fixtures;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RepositoryCallingController {

    private final SampleRepository repository = new SampleRepository();

    @PostMapping("/save")
    public void save() {
        repository.save();
    }
}
