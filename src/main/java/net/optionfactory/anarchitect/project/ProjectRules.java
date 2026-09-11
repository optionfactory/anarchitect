package net.optionfactory.anarchitect.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public class ProjectRules {

    public static final String MAPSTRUCT_UNMAPPED_TARGET_POLICY_ARG = "-Amapstruct.unmappedTargetPolicy=ERROR";
    public static final String MAPSTRUCT_UNMAPPED_TARGET_POLICY_DESCRIPTION = "mapstruct-processor must run with -Amapstruct.unmappedTargetPolicy=ERROR";

    public static List<String> mapstructUnmappedTargetPolicyViolations(MavenProject project) {
        final var compilerPlugins = compilerPlugins(project);
        if (!runsMapstructProcessor(project, compilerPlugins)) {
            return List.of();
        }
        final var policyConfigured = compilerPlugins.stream().anyMatch(ProjectRules::declaresUnmappedTargetPolicyError);
        if (policyConfigured) {
            return List.of();
        }
        return List.of("maven-compiler-plugin runs mapstruct-processor without %s".formatted(MAPSTRUCT_UNMAPPED_TARGET_POLICY_ARG));
    }

    private static List<Plugin> compilerPlugins(MavenProject project) {
        return project.getBuildPlugins().stream()
                .filter(ProjectRules::isCompilerPlugin)
                .toList();
    }

    private static boolean isCompilerPlugin(Plugin plugin) {
        final var groupId = plugin.getGroupId() == null || plugin.getGroupId().isBlank()
                ? "org.apache.maven.plugins"
                : plugin.getGroupId();
        return "org.apache.maven.plugins".equals(groupId) && "maven-compiler-plugin".equals(plugin.getArtifactId());
    }

    private static boolean runsMapstructProcessor(MavenProject project, List<Plugin> compilerPlugins) {
        final var onProcessorPath = compilerPlugins.stream()
                .flatMap(p -> configurations(p).stream())
                .anyMatch(ProjectRules::annotationProcessorPathsDeclareMapstruct);
        final var artifacts = project.getArtifacts() == null ? Set.<Artifact>of() : project.getArtifacts();
        final var onCompileClasspath = artifacts.stream()
                .anyMatch(a -> "org.mapstruct".equals(a.getGroupId()) && "mapstruct-processor".equals(a.getArtifactId()));
        return onProcessorPath || onCompileClasspath;
    }

    private static boolean annotationProcessorPathsDeclareMapstruct(Xpp3Dom config) {
        final var processorPaths = config.getChild("annotationProcessorPaths");
        if (processorPaths == null) {
            return false;
        }
        for (final var path : processorPaths.getChildren("annotationProcessorPath")) {
            final var artifactId = path.getChild("artifactId");
            if (artifactId != null && artifactId.getValue() != null && artifactId.getValue().startsWith("mapstruct")) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresUnmappedTargetPolicyError(Plugin plugin) {
        return configurations(plugin).stream().anyMatch(ProjectRules::declaresUnmappedTargetPolicyError);
    }

    private static List<Xpp3Dom> configurations(Plugin plugin) {
        final var configs = new ArrayList<Xpp3Dom>();
        if (plugin.getConfiguration() instanceof Xpp3Dom config) {
            configs.add(config);
        }
        final var executions = plugin.getExecutions() != null ? plugin.getExecutions() : List.<PluginExecution>of();
        for (final var execution : executions) {
            if (execution.getConfiguration() instanceof Xpp3Dom config) {
                configs.add(config);
            }
        }
        return configs;
    }

    private static boolean declaresUnmappedTargetPolicyError(Xpp3Dom config) {
        final var compilerArgs = config.getChild("compilerArgs");
        if (compilerArgs != null) {
            for (final var arg : compilerArgs.getChildren("arg")) {
                if (isUnmappedTargetPolicyArg(arg.getValue())) {
                    return true;
                }
            }
        }
        final var compilerArgument = config.getChild("compilerArgument");
        if (compilerArgument != null && compilerArgument.getValue() != null) {
            for (final var token : compilerArgument.getValue().split("\\s+")) {
                if (isUnmappedTargetPolicyArg(token)) {
                    return true;
                }
            }
        }
        final var compilerArguments = config.getChild("compilerArguments");
        if (compilerArguments != null) {
            final var policy = compilerArguments.getChild("mapstruct.unmappedTargetPolicy");
            if (policy != null && "ERROR".equals(policy.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnmappedTargetPolicyArg(String arg) {
        return arg != null && arg.replace("\"", "").strip().equals(MAPSTRUCT_UNMAPPED_TARGET_POLICY_ARG);
    }
}
