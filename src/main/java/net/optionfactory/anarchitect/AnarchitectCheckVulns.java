package net.optionfactory.anarchitect;

import java.io.File;
import net.optionfactory.anarchitect.osv.OsvClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.optionfactory.anarchitect.osv.OsvClient.ArtifactInfo;
import net.optionfactory.anarchitect.reports.Reports;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.logging.MessageUtils;
import tools.jackson.databind.json.JsonMapper;

@Mojo(name = "check-vulns", aggregator = true, requiresDependencyResolution = ResolutionScope.TEST)
public class AnarchitectCheckVulns extends AbstractMojo {

    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // reactor modules are resolved as artifacts too: they must not be audited,
            // as their coordinates either do not exist in osv (pointless queries) or
            // collide with published third-party artifacts (false positives)
            final var reactorKeys = reactorProjects.stream()
                    .map(p -> p.getGroupId() + ":" + p.getArtifactId())
                    .collect(Collectors.toSet());

            final var projectDependencies = new HashMap<String, Map<ArtifactInfo, Resolved>>();

            for (final MavenProject project : reactorProjects) {
                final var projectCoords = "%s:%s".formatted(project.getGroupId(), project.getArtifactId());
                final var dependencies = new HashMap<ArtifactInfo, Resolved>();

                for (final var artifact : project.getArtifacts()) {
                    if (reactorKeys.contains(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                        continue;
                    }
                    if (artifact.getVersion() != null) {
                        final var info = new ArtifactInfo(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
                        final var trail = artifact.getDependencyTrail() != null ? artifact.getDependencyTrail() : List.<String>of();
                        dependencies.put(info, new Resolved(artifact.getScope(), trail));
                    }
                }
                projectDependencies.put(projectCoords, dependencies);
            }

            final var uniqueList = projectDependencies.values().stream()
                    .flatMap(m -> m.keySet().stream())
                    .distinct()
                    .toList();

            final var reports = new ArrayList<VulnerabilityReport>();

            if (uniqueList.isEmpty()) {
                getLog().info("No dependencies found to audit.");
            } else {
                getLog().info("Auditing " + uniqueList.size() + " unique dependencies (including transitives) for CVEs...");

                final var vulnMap = new OsvClient(mapper).queryBatch(uniqueList);

                for (final MavenProject project : reactorProjects) {
                    final var projectCoords = "%s:%s".formatted(project.getGroupId(), project.getArtifactId());
                    final var moduleDeps = projectDependencies.getOrDefault(projectCoords, Map.of());

                    final var vulnerableInModule = moduleDeps.keySet().stream()
                            .filter(vulnMap::containsKey)
                            .toList();

                    if (vulnerableInModule.isEmpty()) {
                        continue;
                    }

                    getLog().warn("");
                    getLog().warn(MessageUtils.buffer().strong(":: " + projectCoords).build());

                    for (final var dep : vulnerableInModule) {
                        final var vulnIds = vulnMap.get(dep);
                        final var resolved = moduleDeps.get(dep);

                        for (final var vulnId : vulnIds) {
                            reports.add(new VulnerabilityReport(projectCoords, dep.coords(), dep.version(), vulnId, resolved.trail(), resolved.scope()));
                            getLog().warn(MessageUtils.buffer()
                                    .failure("[VULN] ")
                                    .strong(dep.coords())
                                    .a(":")
                                    .warning(dep.version())
                                    .a(" (" + resolved.scope() + ") ")
                                    .failure(vulnId)
                                    .build());
                            for(var t : resolved.trail()){
                                getLog().warn(MessageUtils.buffer().warning("      [TRAIL] ").strong(t).build());
                            }
                        }
                    }
                }
            }

            final var targetDir =  session.getTopLevelProject().getBuild().getDirectory();
            final var outputFile = new File(targetDir, "anarchitect-vulnerabilities.json");
            Reports.write(mapper, reports, outputFile.toPath());
            final var audited = projectDependencies.entrySet().stream()
                    .flatMap(module -> module.getValue().entrySet().stream()
                            .map(dep -> new AuditedDependency(module.getKey(), dep.getKey().coords(), dep.getKey().version(), dep.getValue().trail(), dep.getValue().scope())))
                    .sorted(Comparator.comparing(AuditedDependency::project).thenComparing(AuditedDependency::artifact))
                    .toList();
            Reports.write(mapper, audited, new File(targetDir, "anarchitect-dependencies.json").toPath());
            if (reports.isEmpty()) {
                getLog().info(" ✓ No known vulnerabilities found in project dependencies.");
            } else {
                getLog().info("");
                getLog().info("Exported vulnerability report to " + outputFile.getAbsolutePath());
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze versions programmatically", e);
        }
    }

    /// How a module resolves a dependency: its Maven scope (`compile`, `runtime`, `provided`, `test`, `system`), which
    /// tells what ships with the module from what only builds or tests it, and the trail that brings it in.
    private record Resolved(String scope, List<String> trail) {
    }

    public record VulnerabilityReport(String project, String artifact, String version, String vuln_id, List<String> trail, String scope) {
    }

    /// Every dependency the audit queried OSV for, so that a later check against OSV can be made without resolving the
    /// project again.
    public record AuditedDependency(String project, String artifact, String version, List<String> trail, String scope) {
    }
}