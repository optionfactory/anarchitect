package net.optionfactory.anarchitect;

import java.util.ArrayList;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo;
import org.codehaus.mojo.versions.rewriting.MutableXMLStreamReader;

import javax.inject.Inject;
import org.codehaus.mojo.versions.utils.ArtifactFactory;
import org.eclipse.aether.RepositorySystem;
import org.apache.maven.wagon.Wagon;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
import java.util.Map;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.shared.utils.logging.MessageUtils;

@Mojo(name = "check-updates", requiresDependencyResolution = ResolutionScope.TEST)
public class AnarchitectCheckUpdates extends AbstractVersionsUpdaterMojo {

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

            final var directDependencyKeys = getProject().getDependencies().stream()
                    .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId())
                    .collect(Collectors.toSet());

            final var dependencies = getProject().getArtifacts().stream()
                    .filter(artifact -> directDependencyKeys.contains(artifact.getGroupId() + ":" + artifact.getArtifactId()))
                    .collect(Collectors.toSet());

            final var upgradableDependencies = analyzeArtifacts(dependencies, false);

            final var plugins = getProject().getPluginArtifacts();

            final var upgradablePlugins = analyzeArtifacts(plugins, true);

            final var combined = Stream.concat(upgradableDependencies.stream(), upgradablePlugins.stream())
                    .toList();

            final int maxCurrentLength = combined.stream()
                    .mapToInt(a -> a.current().length())
                    .max()
                    .orElse(0);
            final int maxLatestLength = combined.stream()
                    .mapToInt(a -> a.latest().length())
                    .max()
                    .orElse(0);

            for (final var artifact : combined) {
                final var message = MessageUtils.buffer()
                        .warning("[upgradeable]")
                        .strong(artifact.type())
                        .a(" ")
                        .warning(String.format("%" + maxCurrentLength + "s", artifact.current()))
                        .a(" → ")
                        .success(String.format("%" + maxLatestLength + "s", artifact.latest()))
                        .a(" ")
                        .a(artifact.coords())
                        .a(" ")
                        .build();

                getLog().info(message);
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze versions programmatically", e);
        }
    }

    public record UpgradableArtifact(String type, String coords, String current, String latest) {

    }

    private List<UpgradableArtifact> analyzeArtifacts(Set<Artifact> artifacts, boolean plugins) throws Exception {
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
            result.add(new UpgradableArtifact(plugins ? "[PLG]" : "[DEP]", coords, currentVersion.toString(), latestVersion.toString()));
        }
        return result;
    }
}
