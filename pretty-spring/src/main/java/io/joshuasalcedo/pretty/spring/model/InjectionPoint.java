package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Something a bean asks Spring to inject.
 */
@Data
@Builder(toBuilder = true)
public class InjectionPoint {
    public enum Kind { CONSTRUCTOR, FIELD, SETTER, BEAN_METHOD_PARAMETER }

    private Kind kind;
    private String name; // parameter or field name
    private String typeName; // the dependency type (container types unwrapped, see collection)
    private boolean collection; // List/Set/Map/Optional/ObjectProvider of a type: any number of beans is fine
    private boolean required;
    private String qualifier; // @Qualifier / @Resource(name)
    private List<String> candidates; // names of the beans that match
    private String resolvedTo; // the bean Spring would inject, when exactly one wins
}
