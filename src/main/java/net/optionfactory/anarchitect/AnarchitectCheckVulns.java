package net.optionfactory.anarchitect;

import net.optionfactory.anarchitect.osv.OsvClient;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.optionfactory.anarchitect.osv.OsvClient.ArtifactInfo;
import net.optionfactory.anarchitect.reports.Reports;
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

    @Parameter(property = "anarchitect.vulnerabilitiesOutputFile", defaultValue = "${session.topLevelProject.build.directory}/anarchitect-vulns.json")
    private File outputFile;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            final var projectDependencies = new HashMap<String, Map<ArtifactInfo, List<String>>>();

            for (final MavenProject project : reactorProjects) {
                final var projectCoords = "%s:%s".formatted(project.getGroupId(), project.getArtifactId());
                final var dependencies = new HashMap<ArtifactInfo, List<String>>();

                for (final var artifact : project.getArtifacts()) {
                    if (artifact.getVersion() != null) {
                        final var info = new ArtifactInfo(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
                        final var trail = artifact.getDependencyTrail() != null ? artifact.getDependencyTrail() : List.<String>of();
                        dependencies.put(info, trail);
                    }
                }
                projectDependencies.put(projectCoords, dependencies);
            }

            final var uniqueList = projectDependencies.values().stream()
                    .flatMap(m -> m.keySet().stream())
                    .distinct()
                    .toList();

            if (uniqueList.isEmpty()) {
                getLog().info("No dependencies found to audit.");
                return;
            }

            getLog().info("Auditing " + uniqueList.size() + " unique dependencies (including transitives) for CVEs...");

            final var vulnMap = new OsvClient(mapper).queryBatch(uniqueList);
            
            final var reports = new ArrayList<VulnerabilityReport>();

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
                    final var trail = moduleDeps.get(dep);

                    for (final var vulnId : vulnIds) {
                        reports.add(new VulnerabilityReport(projectCoords, dep.coords(), dep.version(), vulnId, trail));
                        getLog().warn(MessageUtils.buffer()
                                .failure("[VULN] ")
                                .strong(dep.coords())
                                .a(":")
                                .warning(dep.version())
                                .a(" ")
                                .failure(vulnId)
                                .build());
                        for(var t : trail){
                            getLog().warn(MessageUtils.buffer().warning("      [TRAIL] ").strong(t).build());
                        }
                    }
                }
            }

            if (reports.isEmpty()) {
                getLog().info(" ✓ No known vulnerabilities found in project dependencies.");
                return;
            }

            Reports.write(mapper, reports, outputFile.toPath());
            getLog().info("");
            getLog().info("Exported vulnerability report to " + outputFile.getAbsolutePath());
            
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze versions programmatically", e);
        }
    }

    public record VulnerabilityReport(String project, String artifact, String version, String vuln_id, List<String> trail) {
    }
}