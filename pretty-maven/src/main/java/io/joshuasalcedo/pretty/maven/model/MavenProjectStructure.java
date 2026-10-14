package io.joshuasalcedo.pretty.maven.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * The documentation-relevant parts of a Maven project, optionally with its module tree.
 */
@Data
@Builder
public class MavenProjectStructure {
    private String groupId; // inherited from the parent when not declared
    private String artifactId;
    private String version; // inherited from the parent when not declared
    private String packaging;
    private String name;
    private String description;
    private String url;
    private String inceptionYear;
    private String organizationName;
    private String organizationUrl;
    private MavenCoordinates parent;
    private List<LicenseStructure> licenses;
    private List<DeveloperStructure> developers;
    private ScmStructure scm;
    private String issueManagementUrl;
    private String ciManagementUrl;
    private Map<String, String> properties;
    private List<DependencyStructure> dependencies;
    private List<DependencyStructure> managedDependencies;
    private List<PluginStructure> plugins;
    private List<String> modules; // as declared in <modules>
    private String basedir; // absolute project directory
    private List<MavenProjectStructure> children; // parsed <modules>, empty when not read recursively
}
