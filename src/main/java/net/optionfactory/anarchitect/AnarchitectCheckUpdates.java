package net.optionfactory.anarchitect;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import net.optionfactory.anarchitect.reports.Reports;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
import org.codehaus.mojo.versions.rewriting.MutableXMLStreamReader;
import org.codehaus.mojo.versions.utils.ArtifactFactory;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;
import tools.jackson.databind.json.JsonMapper;

@Mojo(name = "check-updates", aggregator = true, requiresDependencyResolution = ResolutionScope.TEST)
public class AnarchitectCheckUpdates extends AbstractVersionsUpdaterMojo {

    private static final Set<String> PRE_RELEASE_MARKERS = Set.of("alpha", "a", "beta", "b", "milestone", "m", "rc", "cr", "snapshot", "preview", "ea", "dev");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Map<String, ArtifactVersions> versionCache = new HashMap<>();

    @Inject
    public AnarchitectCheckUpdates(
            ArtifactFactory artifactFactory,
            RepositorySystem repositorySystem,
            TransporterProvider transporterProvider,
            Map<String, ChangeRecorder> changeRecorders) throws MojoExecutionException {
        super(artifactFactory, repositorySystem, transporterProvider, changeRecorders);
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

            final var allUpgradablePlugins = new ArrayList<UpgradableArtifact>();
            final var allUpgradableDependencies = new ArrayList<UpgradableArtifact>();

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
                allDeps.addAll(extractImportedBoms(project, reactorKeys));
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

                allUpgradableDependencies.addAll(upgradableDependencies);
                allUpgradablePlugins.addAll(upgradablePlugins);

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
            
            final var targetDir =  session.getTopLevelProject().getBuild().getDirectory();            
            final var dependenciesFile = new File(targetDir, "anarchitect-outdated-dependencies.json");
            Reports.write(JsonMapper.builder().build(), allUpgradableDependencies, dependenciesFile.toPath());
            
            final var pluginsFile = new File(targetDir, "anarchitect-outdated-plugins.json");
            Reports.write(JsonMapper.builder().build(), allUpgradablePlugins, pluginsFile.toPath());
            
            getLog().info("");
            getLog().info("Exported aggregate dependency upgrades report to " + dependenciesFile.getAbsolutePath());
            getLog().info("Exported aggregate plugin upgrades report to " + pluginsFile.getAbsolutePath());
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

        final var effectiveVersions = effectivePluginManagementVersions(project);

        final var result = new HashSet<Artifact>();
        for (final var plugin : originalModel.getBuild().getPluginManagement().getPlugins()) {
            if (plugin.getVersion() == null || plugin.getVersion().isBlank()) {
                continue;
            }
            final String groupId = (plugin.getGroupId() != null && !plugin.getGroupId().isBlank())
                    ? plugin.getGroupId()
                    : "org.apache.maven.plugins";
            // the original model keeps versions as written (${...} included): take the
            // interpolated version from the effective model, as extractManagedDependencies does
            final var effectiveVersion = effectiveVersions.get(groupId + ":" + plugin.getArtifactId());
            if (effectiveVersion == null) {
                continue;
            }
            result.add(new DefaultArtifact(
                    groupId,
                    plugin.getArtifactId(),
                    effectiveVersion,
                    null,
                    "maven-plugin",
                    null,
                    new DefaultArtifactHandler("maven-plugin")
            ));
        }
        return result;
    }

    private Map<String, String> effectivePluginManagementVersions(MavenProject project) {
        final var build = project.getModel().getBuild();
        if (build == null
                || build.getPluginManagement() == null
                || build.getPluginManagement().getPlugins() == null) {
            return Map.of();
        }
        final var versions = new HashMap<String, String>();
        for (final var plugin : build.getPluginManagement().getPlugins()) {
            final String groupId = (plugin.getGroupId() != null && !plugin.getGroupId().isBlank())
                    ? plugin.getGroupId()
                    : "org.apache.maven.plugins";
            if (plugin.getVersion() != null && !plugin.getVersion().isBlank()) {
                versions.put(groupId + ":" + plugin.getArtifactId(), plugin.getVersion());
            }
        }
        return versions;
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
                    .filter(version -> !isPreRelease(version))
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

    /// Whether the version is an alpha, beta, milestone, release candidate, snapshot, preview, early
    /// access or dev build. Release qualifiers (`7.4.16.Final`, `3.1.7.RELEASE`, `33.5.0-jre`) and
    /// versions with more than three numeric segments (`2.12.7.1`) are releases, although maven
    /// parses all of them as qualified.
    static boolean isPreRelease(ArtifactVersion version) {
        return Stream.of(version.toString().toLowerCase(Locale.ROOT).split("[-.]|(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)"))
                .anyMatch(PRE_RELEASE_MARKERS::contains);
    }

    /// The BOMs imported by the `dependencyManagement` the project itself declares. Maven replaces an
    /// import with the BOM's contents in the effective model, so the BOM is found in the original
    /// model only, with its version resolved against the project properties.
    static Set<Artifact> extractImportedBoms(MavenProject project, Set<String> reactorKeys) {
        final var originalModel = project.getOriginalModel();
        if (originalModel == null || originalModel.getDependencyManagement() == null) {
            return Set.of();
        }
        final var properties = project.getProperties();
        final var result = new HashSet<Artifact>();
        for (final var dep : originalModel.getDependencyManagement().getDependencies()) {
            if (!"import".equals(dep.getScope()) || dep.getVersion() == null || reactorKeys.contains(dep.getGroupId() + ":" + dep.getArtifactId())) {
                continue;
            }
            final var version = PROPERTY_REFERENCE.matcher(dep.getVersion())
                    .replaceAll(m -> Matcher.quoteReplacement(properties.getProperty(m.group(1), m.group())));
            if (version.contains("${")) {
                continue;
            }
            result.add(new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), version, dep.getScope(), "pom", null, new DefaultArtifactHandler("pom")));
        }
        return result;
    }
}
