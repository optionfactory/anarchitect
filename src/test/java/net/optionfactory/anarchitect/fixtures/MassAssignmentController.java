package net.optionfactory.anarchitect.fixtures;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MassAssignmentController {

    @PostMapping("/save")
    public void save(@RequestBody SampleEntity entity) {
    }
}
