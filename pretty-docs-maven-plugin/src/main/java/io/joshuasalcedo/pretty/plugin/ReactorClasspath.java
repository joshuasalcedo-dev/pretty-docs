package io.joshuasalcedo.pretty.plugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;

import java.nio.file.Path;
import java.util.*;

/**
 * The external classpath of a set of build modules: their dependencies, transitively, minus the
 * modules themselves (those are analysed from source, so nothing has to be compiled or installed first).
 */
final class ReactorClasspath {

    /**
     * @param classpath    resolved dependency files
     * @param dependencies groupId:artifactId -> version of every external jar dependency, for javadoc links
     */
    record Result(List<Path> classpath, Map<String, String> dependencies) {
    }

    private ReactorClasspath() {
    }

    static Result resolve(ProjectDependenciesResolver resolver, MavenSession session, List<MavenProject> projects,
                          boolean includeTests, Log log) {
        Set<String> reactor = new HashSet<>();
        session.getProjects().forEach(p -> reactor.add(p.getGroupId() + ":" + p.getArtifactId()));
        Set<String> scopes = new HashSet<>(Set.of("compile", "provided", "system"));
        if (includeTests) {
            scopes.add("test");
        }
        DependencyFilter filter = (node, parents) -> {
            Dependency dependency = node.getDependency();
            return dependency == null || (!reactor.contains(key(dependency.getArtifact())) && scopes.contains(dependency.getScope()));
        };

        Set<Path> classpath = new LinkedHashSet<>();
        Map<String, String> dependencies = new TreeMap<>();
        for (MavenProject project : projects) {
            DefaultDependencyResolutionRequest request = new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
            request.setResolutionFilter(filter);
            DependencyResolutionResult result;
            try {
                result = resolver.resolve(request);
            } catch (DependencyResolutionException e) {
                result = e.getResult();
                log.warn("Some dependencies of " + project.getArtifactId() + " could not be resolved; their types stay unresolved ("
                        + e.getMessage().lines().findFirst().orElse("") + ")");
            }
            if (result == null) {
                continue;
            }
            for (Dependency dependency : result.getResolvedDependencies()) {
                Artifact artifact = dependency.getArtifact();
                if (artifact.getFile() != null) {
                    classpath.add(artifact.getFile().toPath());
                }
            }
            for (Dependency dependency : result.getDependencies()) {
                Artifact artifact = dependency.getArtifact();
                if (!reactor.contains(key(artifact)) && "jar".equals(artifact.getExtension()) && artifact.getClassifier().isEmpty()
                        && scopes.contains(dependency.getScope())) {
                    dependencies.putIfAbsent(key(artifact), artifact.getBaseVersion());
                }
            }
        }
        return new Result(new ArrayList<>(classpath), dependencies);
    }

    private static String key(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId();
    }
}
