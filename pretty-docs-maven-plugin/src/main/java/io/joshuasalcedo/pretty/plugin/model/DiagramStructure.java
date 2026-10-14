package io.joshuasalcedo.pretty.plugin.model;

import lombok.Builder;
import lombok.Data;

/**
 * A PlantUML diagram written next to the site, as source and (when rendering worked) SVG.
 */
@Data
@Builder
public class DiagramStructure {
    public enum Kind { PACKAGE_CLASSES, MODULE_DEPENDENCIES, SPRING_BEANS }

    private String id; // file name stem, unique within the site
    private Kind kind;
    private String title;
    private String moduleId; // for PACKAGE_CLASSES
    private String packageName; // for PACKAGE_CLASSES
    private String pumlPath; // relative to the site root, e.g. diagrams/x.puml
    private String svgPath; // relative to the site root; null when rendering failed or was disabled
    private int elementCount; // classes, modules or beans shown
}
