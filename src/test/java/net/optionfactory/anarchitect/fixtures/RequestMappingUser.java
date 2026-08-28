package net.optionfactory.anarchitect.fixtures;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RequestMappingUser {

    @RequestMapping("/all-verbs")
    public String allVerbs() {
        return "";
    }

    @RequestMapping(value = "/get-only", method = RequestMethod.GET)
    public String getOnly() {
        return "";
    }
}
