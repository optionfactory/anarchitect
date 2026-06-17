package net.optionfactory.anarchitect;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.FailureReport;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.optionfactory.anarchitect.Checks.RuleTags;
import net.optionfactory.anarchitect.Checks.TaggedRule;
import net.optionfactory.anarchitect.Checks.ViolationType;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.logging.MessageUtils;

@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.COMPILE)
public class AnarchitectCheck extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.compileClasspathElements}", readonly = true, required = true)
    private List<String> compileClasspathElements;

    @Parameter(property = "anarchitect.tags", defaultValue = "RECOMMENDED")
    private Set<RuleTags> tags;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var log = getLog();
        if (!outputDirectory.exists()) {
            log.info("Output directory does not exist, skipping checks.");
            return;
        }
        final var originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {

            final var classpathUrls = compileClasspathElements.stream().map(AnarchitectCheck::classPathElementToUrl).toArray(i -> new URL[i]);
            final var projectClassLoader = new URLClassLoader(classpathUrls, originalClassLoader);
            Thread.currentThread().setContextClassLoader(projectClassLoader);

            log.info("Importing classes from: %s".formatted(outputDirectory.getAbsolutePath()));
            final var classes = new ClassFileImporter().importPath(outputDirectory.toPath());
            final var commonAncestorPackage = commonAncestorPackage(classes);
            log.info("Imported %s classes with root package %s".formatted(classes.size(), commonAncestorPackage));
            final var rules = Checks.makeRules(commonAncestorPackage, tags);
            log.info("Using: %s rules matching tags: %s".formatted(rules.length, tags));
            final var rulesAndResults = Stream.of(rules).map(r -> new RuleAndReport(r, r.rule().evaluate(classes).getFailureReport())).toList();
            for (final var ruleAndResult : rulesAndResults) {
                final var report = ruleAndResult.report();
                log.info("%s: %s".formatted(MessageUtils.buffer().strong("rule").build(), ruleAndResult.conf().rule()));
                if (report.isEmpty()) {
                    log.info(MessageUtils.buffer().success(" ✓ passed").build());
                }
                for (final var detail : report.getDetails()) {
                    final var lines = detail.split("\r*\n");
                    for (int i = 0; i != lines.length; i++) {
                        if (ruleAndResult.conf().violationType() == ViolationType.FAILURE) {
                            log.info(MessageUtils.buffer().failure(i == 0 ? " ✗ failed: " : "           ").a(lines[i]).build());
                        } else {
                            log.info(MessageUtils.buffer().warning(i == 0 ? " ⚡ warning: " : "           ").a(lines[i]).build());
                        }
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private static String commonAncestorPackage(JavaClasses classes) {
        final var packages = classes.stream()
                .map(c -> c.getPackageName().split("\\."))
                .toList();

        if (packages.isEmpty()) {
            return "";
        }

        final var firstPackage = packages.get(0);
        int commonLength = firstPackage.length;

        for (final var currentPackage : packages) {
            commonLength = Math.min(commonLength, currentPackage.length);
            for (int i = 0; i < commonLength; i++) {
                if (!firstPackage[i].equals(currentPackage[i])) {
                    commonLength = i;
                    break;
                }
            }
        }

        return String.join(".", Arrays.copyOfRange(firstPackage, 0, commonLength));
    }

    private static URL classPathElementToUrl(String cpe) {
        try {
            return new File(cpe).toURI().toURL();
        } catch (MalformedURLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record RuleAndReport(TaggedRule conf, FailureReport report) {

    }
}
