package io.joshuasalcedo.pretty.plugin.model;

import io.joshuasalcedo.pretty.maven.model.MavenProjectStructure;
import io.joshuasalcedo.pretty.model.JavaFileStructure;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * One Maven module: its POM metadata and its parsed sources.
 */
@Data
@Builder
public class ModuleDocumentation {
    private String id; // groupId:artifactId
    private String parentId; // nearest enclosing module in the build, null for the root (and top-level TypeScript packages)
    private String language; // "java" (a Maven module) or "typescript" (a package or workspace); each forms its own tree
    private String relativePath; // module directory relative to the root module, "" for the root
    private MavenProjectStructure project; // children are not filled in; use parentId
    private List<JavaFileStructure> files; // filePath is relative to the root module
    private List<JavaFileStructure> testFiles; // empty unless test sources are included
    private ModuleStatistics statistics;
    private String readmeHtml; // README.md rendered to HTML (unsanitized; the site sanitizes it)
    private List<AssetEntry> assets; // images in the module directory (icons, logos, ...), copied into the site
}
