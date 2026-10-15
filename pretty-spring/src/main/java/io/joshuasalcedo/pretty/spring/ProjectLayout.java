package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.spring.model.ConfigurationFileStructure;
import io.joshuasalcedo.pretty.spring.model.ConfigurationKeyMetadata;

import java.util.List;
import java.util.Map;

/**
 * What the build knows beyond the sources: which module each source root belongs to, how modules
 * depend on each other, the auto-configurations they register, their configuration files and the
 * configuration keys libraries document. With it, beans are worked out per application.
 *
 * @param moduleOfSourceRoot  source directory (absolute, '/' separators) -> module id
 * @param moduleDependencies  module id -> build modules it depends on (directly is enough)
 * @param autoConfigurations  module id -> classes its AutoConfiguration.imports (or spring.factories) lists
 * @param configurationFiles  application*.yml / .properties of every module
 * @param libraryKeys         keys documented by libraries on the classpath, by name; empty when unknown
 */
public record ProjectLayout(Map<String, String> moduleOfSourceRoot,
                            Map<String, List<String>> moduleDependencies,
                            Map<String, List<String>> autoConfigurations,
                            List<ConfigurationFileStructure> configurationFiles,
                            Map<String, ConfigurationKeyMetadata> libraryKeys) {
}
