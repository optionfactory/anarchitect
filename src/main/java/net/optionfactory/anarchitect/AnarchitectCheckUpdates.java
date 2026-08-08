package net.optionfactory.anarchitect;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import net.optionfactory.anarchitect.reports.Reports;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.apache.maven.wagon.Wagon;
import org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
import org.codehaus.mojo.versions.rewriting.MutableXMLStreamReader;
import org.codehaus.mojo.versions.utils.ArtifactFactory;
import org.eclipse.aether.RepositorySystem;

@Mojo(name = "check-updates", aggregator = true, requiresDependencyResolution = ResolutionScope.TEST)
public class AnarchitectCheckUpdates extends AbstractVersionsUpdaterMojo {

    @Parameter(property = "anarchitect.updatesOutputFile", defaultValue = "${session.topLevelProject.build.directory}/anarchitect-updates.json")
    private File outputFile;

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

            for (final var prj : reactorProjects) {
                final var moduleCoords = "%s:%s".formatted(prj.getGroupId(), prj.getArtifactId());

                final var directDependencyKeys = prj.getDependencies().stream()
                        .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId())
                        .filter(key -> !reactorKeys.contains(key))
                        .collect(Collectors.toSet());

                final var dependencies = prj.getArtifacts().stream()
                        .filter(artifact -> directDependencyKeys.contains(artifact.getGroupId() + ":" + artifact.getArtifactId()))
                        .collect(Collectors.toSet());

                final var upgradableDependencies = analyzeArtifacts(moduleCoords, dependencies, false);
                final var upgradablePlugins = analyzeArtifacts(moduleCoords, prj.getPluginArtifacts(), true);

                final var combinedForModule = Stream.concat(upgradableDependencies.stream(), upgradablePlugins.stream())
                        .toList();

                if (combinedForModule.isEmpty()) {
                    continue;
                }

                allUpgradableArtifacts.addAll(combinedForModule);

                getLog().info("");
                getLog().info(MessageUtils.buffer().strong(":: " + prj.getArtifactId()).build());

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
                            .a("plugin".equals(artifact.type()) ? pluginLabel : depLabel)
                            .a(" ")
                            .warning(String.format("%" + maxCurrentLength + "s", artifact.current()))
                            .a(" -> ")
                            .success(String.format("%" + maxLatestLength + "s", artifact.latest()))
                            .a(" ")
                            .a(artifact.coords())
                            .a(" ")
                            .build();
                    getLog().info(message);
                }
            }

            if (outputFile != null) {
                Reports.write(outputFile.toPath(), allUpgradableArtifacts);
                getLog().info("");
                getLog().info("Exported aggregate updates report to " + outputFile.getAbsolutePath());
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze versions programmatically", e);
        }
    }

    public record UpgradableArtifact(String module, String type, String coords, String current, String latest) {
    }

    private List<UpgradableArtifact> analyzeArtifacts(String moduleCoords, Set<Artifact> artifacts, boolean plugins) throws Exception {
        final var result = new ArrayList<UpgradableArtifact>();
        for (final var artifact : artifacts) {
            final var artifactVersions = getHelper().lookupArtifactVersions(artifact, plugins);
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
            result.add(new UpgradableArtifact(moduleCoords, plugins ? "plugin" : "dependency", coords, currentVersion.toString(), latestVersion.toString()));
        }
        return result;
    }
}