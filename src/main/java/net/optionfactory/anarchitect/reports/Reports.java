package net.optionfactory.anarchitect.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.optionfactory.anarchitect.AnarchitectCheckUpdates.UpgradableArtifact;

public class Reports {

    public static void write(Path destination, List<UpgradableArtifact> artifacts) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }

        try (var writer = Files.newBufferedWriter(destination)) {
            if (artifacts == null || artifacts.isEmpty()) {
                writer.write("[]\n");
                return;
            }

            writer.write("[\n");
            for (int i = 0; i < artifacts.size(); i++) {
                UpgradableArtifact a = artifacts.get(i);
                writer.write("""
                {
                    "module": "%s",
                    "type": "%s",
                    "coords": "%s",
                    "current": "%s",
                    "latest": "%s"
                }""".formatted(
                        escape(a.module()),
                        escape(a.type()),
                        escape(a.coords()),
                        escape(a.current()),
                        escape(a.latest())));

                if (i < artifacts.size() - 1) {
                    writer.write(",\n");
                }
            }
            writer.write("\n]\n");
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}