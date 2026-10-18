package io.joshuasalcedo.pretty.quality.model;

import lombok.Builder;
import lombok.Data;

/**
 * One problem a code quality tool reports.
 */
@Data
@Builder(toBuilder = true)
public class QualityFinding {
    public enum Priority { HIGH, MEDIUM, LOW }

    private String tool; // PMD, SpotBugs, TypeScript
    private String rule; // e.g. CloseResource, NP_NULL_ON_SOME_PATH, TS2345
    private String category; // e.g. Error prone, Correctness, Type error
    private Priority priority;
    private String message;
    private String moduleId;
    private String file; // relative to the documented project root once the site is built
    private int line; // 0 when unknown
    private int endLine;
    private String typeName; // the (outermost known) type it is in, qualified; null when unknown
    private String memberName; // method or field, when the tool says
    private String url; // the rule's documentation
}
