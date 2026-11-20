package net.optionfactory.anarchitect;

import java.util.Set;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AnarchitectCheckUpdatesTest {

    @Test
    public void qualifiedReleasesAreUpgradeCandidates() {
        for (final var version : Set.of("7.4.16.Final", "3.1.7.RELEASE", "1.2.3.GA", "33.5.0-jre", "2.12.7.1", "4.2.0")) {
            Assertions.assertFalse(AnarchitectCheckUpdates.isPreRelease(new DefaultArtifactVersion(version)), version + " is a release");
        }
    }

    @Test
    public void preReleasesAreNotUpgradeCandidates() {
        for (final var version : Set.of("7.1.0-RC1", "3.0.0-rc.1", "7.0.0.CR1", "5.0.0-M2", "7.0.0.Beta1", "2.0.0-alpha-1", "1.0-SNAPSHOT", "26-ea", "2.0.0-preview1")) {
            Assertions.assertTrue(AnarchitectCheckUpdates.isPreRelease(new DefaultArtifactVersion(version)), version + " is a pre-release");
        }
    }

    @Test
    public void importedBomsAreCheckedWithTheirVersionResolved() {
        final var bom = new Dependency();
        bom.setGroupId("org.springframework");
        bom.setArtifactId("spring-framework-bom");
        bom.setVersion("${spring.version}");
        bom.setType("pom");
        bom.setScope("import");
        final var jar = new Dependency();
        jar.setGroupId("org.hibernate.orm");
        jar.setArtifactId("hibernate-core");
        jar.setVersion("7.4.1.Final");
        final var model = new Model();
        model.addProperty("spring.version", "7.1.0");
        model.setDependencyManagement(new DependencyManagement());
        model.getDependencyManagement().addDependency(bom);
        model.getDependencyManagement().addDependency(jar);
        final var project = new MavenProject(model);
        project.setOriginalModel(model);

        final var boms = AnarchitectCheckUpdates.extractImportedBoms(project, Set.of());

        Assertions.assertEquals(1, boms.size(), "only the import is taken: plain managed dependencies are read from the effective model");
        final var found = boms.iterator().next();
        Assertions.assertEquals("org.springframework:spring-framework-bom:7.1.0", found.getGroupId() + ":" + found.getArtifactId() + ":" + found.getVersion(), "the BOM version is resolved against the project properties");
    }
}
