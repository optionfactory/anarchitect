package net.optionfactory.anarchitect;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import net.optionfactory.anarchitect.reports.Reports;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.maven.wagon.Wagon;
import org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
import org.codehaus.mojo.versions.rewriting.MutableXMLStreamReader;
import org.codehaus.mojo.versions.utils.ArtifactFactory;
import org.eclipse.aether.RepositorySystem;
import tools.jackson.databind.json.JsonMapper;

@Mojo(name = "check-updates", aggregator = true, requiresDependencyResolution = ResolutionScope.TEST)
public class AnarchitectCheckUpdates extends AbstractVersionsUpdaterMojo {

    @Parameter(property = "anarchitect.updatesOutputFile", defaultValue = "${session.topLevelProject.build.directory}/anarchitect-updates.json")
    private File outputFile;

    private final Map<String, ArtifactVersions> versionCache = new HashMap<>();

    @Inject
    public AnarchitectCheckUpdates(
            ArtifactFactory artifactFactory,
            RepositorySystem repositorySystem,
            Map<String, Wagon> wagonMap,
            Map<String, ChangeRecorder> changeRecorders) throws MojoExecutionException {
        super(artifactFactory, repositorySystem, wagonMap, changeRecorders);
    }

    @Override
    public boolean getAllowSnapshots() {
        return false;
    }

    @Override
    protected void update(MutableXMLStreamReader pom) throws MojoExecutionException, MojoFailureException {
        try {
            final var reactorKeys = reactorProjects.stream()
                    .map(p -> p.getGroupId() + ":" + p.getArtifactId())
                    .collect(Collectors.toSet());

            final var allUpgradableArtifacts = new ArrayList<UpgradableArtifact>();

            for (final MavenProject project : reactorProjects) {
                final String projectCoords = "%s:%s".formatted(project.getGroupId(), project.getArtifactId());

                final var managedDeps = extractManagedDependencies(project, reactorKeys);
                final var managedKeys = managedDeps.stream()
                        .map(a -> a.getGroupId() + ":" + a.getArtifactId())
                        .collect(Collectors.toSet());

                final var directDependencyKeys = project.getDependencies().stream()
                        .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId())
                        .filter(key -> !reactorKeys.contains(key) && !managedKeys.contains(key))
                        .collect(Collectors.toSet());

                final var directDeps = project.getArtifacts().stream()
                        .filter(artifact -> directDependencyKeys.contains(artifact.getGroupId() + ":" + artifact.getArtifactId()))
                        .collect(Collectors.toSet());

                final var allDeps = new HashSet<Artifact>();
                allDeps.addAll(managedDeps);
                allDeps.addAll(directDeps);

                final var managedPlugins = extractManagedPlugins(project);
                final var directPlugins = project.getPluginArtifacts();

                final var allPlugins = new HashSet<Artifact>();
                allPlugins.addAll(managedPlugins);
                allPlugins.addAll(directPlugins);

                final var upgradableDependencies = analyzeArtifacts(projectCoords, allDeps, "dependency", false);
                final var upgradablePlugins = analyzeArtifacts(projectCoords, allPlugins, "plugin", true);

                final var combinedForModule = Stream.concat(upgradableDependencies.stream(), upgradablePlugins.stream())
                        .toList();

                if (combinedForModule.isEmpty()) {
                    continue;
                }

                allUpgradableArtifacts.addAll(combinedForModule);

                getLog().info("");
                getLog().info(MessageUtils.buffer().strong(":: " + projectCoords).build());

                final int maxCurrentLength = combinedForModule.stream()
                        .mapToInt(a -> a.current().length())
                        .max()
                        .orElse(0);

                final int maxLatestLength = combinedForModule.stream()
                        .mapToInt(a -> a.latest().length())
                        .max()
                        .orElse(0);

                final var depLabel = MessageUtils.buffer().strong("[DEP]").build();
                final var pluginLabel = MessageUtils.buffer().project("[PLG]").build();

                for (final var artifact : combinedForModule) {
                    final var message = MessageUtils.buffer()
                            .warning("[upgradeable]")
                            .a("plugin".equals(artifact.kind()) ? pluginLabel : depLabel)
                            .a(" ")
                            .warning(String.format("%" + maxCurrentLength + "s", artifact.current()))
                            .a(" -> ")
                            .success(String.format("%" + maxLatestLength + "s", artifact.latest()))
                            .a(" ")
                            .a(artifact.artifact())
                            .a(" ")
                            .build();
                    getLog().info(message);
                }
            }

            Reports.write(JsonMapper.builder().build(), allUpgradableArtifacts, outputFile.toPath());
            getLog().info("");
            getLog().info("Exported aggregate updates report to " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze versions programmatically", e);
        }
    }

    public record UpgradableArtifact(String project, String kind, String artifact, String current, String latest) {

    }

    private Set<Artifact> extractManagedDependencies(MavenProject project, Set<String> reactorKeys) {
        final var originalModel = project.getOriginalModel();
        if (originalModel == null
                || originalModel.getDependencyManagement() == null
                || originalModel.getDependencyManagement().getDependencies() == null) {
            return Set.of();
        }

        final var declaredKeys = originalModel.getDependencyManagement().getDependencies().stream()
                .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId())
                .collect(Collectors.toSet());

        final var effectiveDepMgmt = project.getDependencyManagement();
        if (effectiveDepMgmt == null || effectiveDepMgmt.getDependencies() == null) {
            return Set.of();
        }

        final var result = new HashSet<Artifact>();
        for (final var dep : effectiveDepMgmt.getDependencies()) {
            final String key = dep.getGroupId() + ":" + dep.getArtifactId();
            if (declaredKeys.contains(key) && !reactorKeys.contains(key) && dep.getVersion() != null && !dep.getVersion().isBlank()) {
                final String type = dep.getType() != null ? dep.getType() : "jar";
                result.add(new DefaultArtifact(
                        dep.getGroupId(),
                        dep.getArtifactId(),
                        dep.getVersion(),
                        dep.getScope(),
                        type,
                        dep.getClassifier(),
                        new DefaultArtifactHandler(type)
                ));
            }
        }
        return result;
    }

    private Set<Artifact> extractManagedPlugins(MavenProject project) {
        final var originalModel = project.getOriginalModel();
        if (originalModel == null
                || originalModel.getBuild() == null
                || originalModel.getBuild().getPluginManagement() == null
                || originalModel.getBuild().getPluginManagement().getPlugins() == null) {
            return Set.of();
        }

        final var result = new HashSet<Artifact>();
        for (final var plugin : originalModel.getBuild().getPluginManagement().getPlugins()) {
            if (plugin.getVersion() == null || plugin.getVersion().isBlank()) {
                continue;
            }
            final String groupId = (plugin.getGroupId() != null && !plugin.getGroupId().isBlank())
                    ? plugin.getGroupId()
                    : "org.apache.maven.plugins";

            result.add(new DefaultArtifact(
                    groupId,
                    plugin.getArtifactId(),
                    plugin.getVersion(),
                    null,
                    "maven-plugin",
                    null,
                    new DefaultArtifactHandler("maven-plugin")
            ));
        }
        return result;
    }

    private List<UpgradableArtifact> analyzeArtifacts(String projectCoords, Set<Artifact> artifacts, String kind, boolean isPlugin) throws Exception {
        final var result = new ArrayList<UpgradableArtifact>();
        for (final var artifact : artifacts) {
            final String cacheKey = (isPlugin ? "plg:" : "dep:") + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();

            var artifactVersions = versionCache.get(cacheKey);
            if (artifactVersions == null) {
                artifactVersions = getHelper().lookupArtifactVersions(artifact, isPlugin);
                versionCache.put(cacheKey, artifactVersions);
            }

            final var currentVersion = artifactVersions.getCurrentVersion();
            if (currentVersion == null) {
                continue;
            }

            final var candidateUpdates = Arrays.stream(artifactVersions.getNewerVersions(currentVersion.toString(), Optional.empty(), false, false))
                    .filter(version -> version.getQualifier() == null)
                    .toList();

            if (candidateUpdates.isEmpty()) {
                continue;
            }

            final var latestVersion = candidateUpdates.getLast();
            final var coords = "%s:%s".formatted(artifact.getGroupId(), artifact.getArtifactId());
            result.add(new UpgradableArtifact(projectCoords, kind, coords, currentVersion.toString(), latestVersion.toString()));
        }
        return result;
    }
}
