package net.optionfactory.anarchitect.project;

import java.util.List;
import java.util.Set;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ProjectRulesTest {

    @Test
    public void mapstructProcessorWithoutPolicyIsReported() {
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(
                compilerPlugin(
                        processorPath("mapstruct-processor"))));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
        Assertions.assertTrue(violations.getFirst().contains("-Amapstruct.unmappedTargetPolicy=ERROR"), () -> violations.toString());
    }

    @Test
    public void implicitCompilerPluginGroupIdIsHandled() {
        final var plugin = compilerPlugin(processorPath("mapstruct-processor"));
        plugin.setGroupId(null);
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(plugin));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
    }

    @Test
    public void policyInCompilerArgsPasses() {
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(
                compilerPlugin(
                        processorPath("mapstruct-processor"),
                        compilerArgs("-Amapstruct.unmappedTargetPolicy=ERROR"))));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void quotedPolicyArgPasses() {
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(
                compilerPlugin(
                        processorPath("mapstruct-processor"),
                        compilerArgs("\"-Amapstruct.unmappedTargetPolicy=ERROR\""))));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void policyInCompilerArgumentPasses() {
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(
                compilerPlugin(
                        processorPath("mapstruct-processor"),
                        dom("compilerArgument", "-proc:full -Amapstruct.unmappedTargetPolicy=ERROR"))));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void policyInDeprecatedCompilerArgumentsPasses() {
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(
                compilerPlugin(
                        processorPath("mapstruct-processor"),
                        dom("compilerArguments", null,
                                dom("mapstruct.unmappedTargetPolicy", "ERROR")))));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void lenientPolicyIsReported() {
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(
                compilerPlugin(
                        processorPath("mapstruct-processor"),
                        compilerArgs("-Amapstruct.unmappedTargetPolicy=WARN"))));
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
    }

    @Test
    public void policyInExecutionConfigurationPasses() {
        final var plugin = compilerPlugin(processorPath("mapstruct-processor"));
        final var execution = new PluginExecution();
        final var configuration = new Xpp3Dom("configuration");
        configuration.addChild(compilerArgs("-Amapstruct.unmappedTargetPolicy=ERROR"));
        execution.setConfiguration(configuration);
        plugin.setExecutions(List.of(execution));
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(plugin));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void processorOnCompileClasspathWithoutPolicyIsReported() {
        final var project = project();
        project.setArtifacts(Set.of(artifact("org.mapstruct", "mapstruct-processor")));
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project);
        Assertions.assertEquals(1, violations.size(), () -> violations.toString());
    }

    @Test
    public void processorOnCompileClasspathWithPolicyPasses() {
        final var project = project(compilerPlugin(compilerArgs("-Amapstruct.unmappedTargetPolicy=ERROR")));
        project.setArtifacts(Set.of(artifact("org.mapstruct", "mapstruct-processor")));
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project);
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void mapstructCoreOnCompileClasspathAloneIsIgnored() {
        final var project = project();
        project.setArtifacts(Set.of(artifact("org.mapstruct", "mapstruct")));
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project);
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void otherProcessorsAreIgnored() {
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(
                compilerPlugin(
                        processorPath("lombok"))));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void missingCompilerPluginIsIgnored() {
        final var plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-surefire-plugin");
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(plugin));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    @Test
    public void compilerPluginWithoutConfigurationIsIgnored() {
        final var plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        final var violations = ProjectRules.mapstructUnmappedTargetPolicyViolations(project(plugin));
        Assertions.assertTrue(violations.isEmpty(), () -> violations.toString());
    }

    private static MavenProject project(Plugin... plugins) {
        final var build = new Build();
        build.setPlugins(List.of(plugins));
        final var model = new Model();
        model.setBuild(build);
        final var project = new MavenProject(model);
        project.setBuild(build);
        return project;
    }

    private static DefaultArtifact artifact(String groupId, String artifactId) {
        return new DefaultArtifact(groupId, artifactId, "1.6.3", "provided", "jar", null, new DefaultArtifactHandler("jar"));
    }

    private static Plugin compilerPlugin(Xpp3Dom... children) {
        final var plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-compiler-plugin");
        final var configuration = new Xpp3Dom("configuration");
        for (final var child : children) {
            configuration.addChild(child);
        }
        plugin.setConfiguration(configuration);
        return plugin;
    }

    private static Xpp3Dom processorPath(String artifactId) {
        return dom("annotationProcessorPaths", null,
                dom("annotationProcessorPath", null,
                        dom("artifactId", artifactId)));
    }

    private static Xpp3Dom compilerArgs(String... args) {
        final Xpp3Dom[] argDoms = List.of(args).stream().map(arg -> dom("arg", arg)).toArray(Xpp3Dom[]::new);
        return dom("compilerArgs", null, argDoms);
    }

    private static Xpp3Dom dom(String name, String value, Xpp3Dom... children) {
        final var dom = new Xpp3Dom(name);
        if (value != null) {
            dom.setValue(value);
        }
        for (final var child : children) {
            dom.addChild(child);
        }
        return dom;
    }
}
