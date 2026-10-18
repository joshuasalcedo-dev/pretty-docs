package io.joshuasalcedo.pretty.plugin.model;

import lombok.Builder;
import lombok.Data;

/**
 * An image in a module's directory (an icon, logo or picture under public/, assets/, static/, ...),
 * copied into the site so it can be shown.
 */
@Data
@Builder
public class AssetEntry {
    private String path; // relative to the module directory, e.g. public/favicon.svg
    private String href; // where the site serves the copy, relative to the site root
    private long size; // bytes
}
