package net.optionfactory.anarchitect.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

public class Reports {

    public static void write(JsonMapper mapper, Object value, Path destination) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        mapper.writeValue(destination.toFile(), value);
    }
}
