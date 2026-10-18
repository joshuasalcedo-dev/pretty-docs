package io.joshuasalcedo.pretty.quality.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Code quality findings for the documented project, and which tools produced them.
 */
@Data
@Builder
public class QualityStructure {
    private List<ToolRun> tools;
    private List<QualityFinding> findings;

    /** A tool that ran (or was skipped, with why). */
    @Data
    @Builder
    public static class ToolRun {
        private String name;
        private String version;
        private boolean ran;
        private String note; // what it looked at, or why it did not run
        private int findings;
    }
}
