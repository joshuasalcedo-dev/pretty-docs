package io.joshuasalcedo.pretty.plugin.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Everything the documentation site renders. Serialized as {@code docs.json} and as
 * {@code docs-data.js} ({@code window.__PRETTY_DOCS__ = ...}) so the site works from {@code file://}.
 * Bump {@link #SCHEMA_VERSION} on breaking changes; the site checks it.
 */
@Data
@Builder
public class DocumentationSite {
    public static final int SCHEMA_VERSION = 2; // 2: docs-data.js holds an outline; full module sources are in modules/*.js

    private int schemaVersion;
    private String title;
    private String generator; // e.g. "pretty-docs-maven-plugin 0.1.0"
    private String generatedAt; // ISO-8601 instant
    private String visibility; // lowest access level included: public, protected, package or private
    private String mode; // "resolved" (javadoc engine filled in names and inherited docs) or "source"
    private String rootModuleId;
    private List<ModuleDocumentation> modules; // reactor order; parents before children
    private List<ExternalDocumentation> externalDocs; // javadoc sites for types outside the project
    private io.joshuasalcedo.pretty.spring.model.SpringStructure spring; // Spring wiring, null when the project does not use Spring
    private List<DiagramStructure> diagrams; // PlantUML diagrams under diagrams/
    private transient java.util.Map<String, java.nio.file.Path> readmeAssets; // site path -> README image to copy; not serialized
}
