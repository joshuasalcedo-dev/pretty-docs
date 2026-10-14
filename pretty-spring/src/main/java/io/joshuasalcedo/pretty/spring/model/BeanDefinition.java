package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A bean Spring would create.
 */
@Data
@Builder
public class BeanDefinition {
    private String name; // bean name (explicit, or the default Spring derives)
    private List<String> aliases; // additional @Bean names
    private BeanKind kind;
    private String typeName; // qualified (or simple, when unresolved) bean type
    private List<String> assignableTo; // the type and its supertypes, for matching injection points
    private String declaringType; // the class itself, or the configuration declaring the @Bean method
    private String declaringMember; // @Bean method name, null otherwise
    private String scope; // singleton unless @Scope says otherwise
    private boolean primary;
    private boolean lazy;
    private List<String> qualifiers; // @Qualifier values
    private List<String> profiles; // @Profile values
    private List<String> conditions; // @ConditionalOn... as written
    private List<InjectionPoint> dependencies;
    private List<String> injectedInto; // names of beans that depend on this one
    private boolean inComponentScan; // false when outside every scan package (only meaningful for scanned stereotypes)
    private int line;
}
