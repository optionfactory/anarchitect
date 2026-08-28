package net.optionfactory.anarchitect.fixtures;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HandlerVisibilityController {

    @GetMapping("/public-handler")
    public String publicHandler() {
        return "";
    }

    @GetMapping("/package-private-handler")
    String packagePrivateHandler() {
        return "";
    }
}
