package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

/**
 * A JPA entity.
 */
@Data
@Builder
public class EntityStructure {
    private String typeName;
    private String entityName;
    private String tableName; // @Table(name), null when defaulted
    private String idField;
}
