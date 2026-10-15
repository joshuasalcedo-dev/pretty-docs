package io.joshuasalcedo.pretty.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the site data so a large build opens quickly. The main data keeps a light outline of
 * every type: what the sidebar, search, icons, links and statistics need. Each module's full
 * sources go into their own script, which the site loads when a page of that module is opened.
 * Scripts, not fetch(), so the site keeps working from file://.
 */
final class ModuleDataSplitter {

    static final String DIRECTORY = "modules";

    /** Type members the outline keeps (everything else waits for the module's own data). */
    private static final List<String> TYPE_FIELDS = List.of(
            "name", "qualifiedName", "kind", "modifiers", "superclass", "interfaces", "startLine", "endLine");
    private static final List<String> EMPTY_TYPE_LISTS = List.of(
            "typeParameters", "constructors", "enumConstants", "recordComponents", "usedBy", "inheritedMethods", "inheritedFields");

    record Result(JsonObject outline, Map<String, String> moduleScripts) {
    }

    private ModuleDataSplitter() {
    }

    /**
     * @param site the full site JSON; left unchanged
     * @return the outline for docs-data.js, and per module a script path -> script content
     */
    static Result split(JsonObject site, Gson gson) {
        JsonObject outline = site.deepCopy();
        Map<String, String> scripts = new LinkedHashMap<>();
        for (JsonElement element : outline.getAsJsonArray("modules")) {
            JsonObject module = element.getAsJsonObject();
            String id = module.get("id").getAsString();
            String path = DIRECTORY + "/" + DiagramGenerator.slug(id) + ".js";
            JsonObject data = new JsonObject();
            data.add("files", module.has("files") ? module.get("files") : new JsonArray());
            data.add("testFiles", module.has("testFiles") ? module.get("testFiles") : new JsonArray());
            scripts.put(path, "(window.__PRETTY_DOCS_MODULES__ = window.__PRETTY_DOCS_MODULES__ || {})["
                    + gson.toJson(id) + "] = " + gson.toJson(data) + ";\n");
            module.add("files", outlineFiles(data.getAsJsonArray("files")));
            module.add("testFiles", new JsonArray());
            module.addProperty("dataScript", path);
        }
        return new Result(outline, scripts);
    }

    private static JsonArray outlineFiles(JsonArray files) {
        JsonArray result = new JsonArray();
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            JsonObject outline = new JsonObject();
            for (String key : List.of("fileName", "packageName", "filePath")) {
                if (file.has(key)) {
                    outline.add(key, file.get(key));
                }
            }
            // package-info.java's javadoc describes the package in the sidebar and package lists
            boolean packageInfo = "package-info.java".equals(file.has("fileName") ? file.get("fileName").getAsString() : null);
            outline.add("fileComments", packageInfo && file.has("fileComments") ? file.get("fileComments") : new JsonArray());
            outline.add("imports", new JsonArray());
            JsonArray types = new JsonArray();
            if (file.has("types")) {
                file.getAsJsonArray("types").forEach(type -> types.add(outlineType(type.getAsJsonObject())));
            }
            outline.add("types", types);
            result.add(outline);
        }
        return result;
    }

    private static JsonObject outlineType(JsonObject type) {
        JsonObject outline = new JsonObject();
        outline.addProperty("outline", true);
        for (String key : TYPE_FIELDS) {
            if (type.has(key)) {
                outline.add(key, type.get(key));
            }
        }
        if (!outline.has("modifiers")) {
            outline.add("modifiers", new JsonArray());
        }
        if (!outline.has("interfaces")) {
            outline.add("interfaces", new JsonArray());
        }
        outline.add("annotations", keep(type.getAsJsonArray("annotations"), "name", "qualifiedName"));
        // search lists methods with their parameter types; statistics rank them by complexity
        outline.add("methods", keep(type.getAsJsonArray("methods"), "name", "parameters", "modifiers", "cyclomaticComplexity", "lineCount"));
        outline.add("fields", keep(type.getAsJsonArray("fields"), "name", "modifiers"));
        for (String key : EMPTY_TYPE_LISTS) {
            outline.add(key, new JsonArray());
        }
        JsonArray nested = new JsonArray();
        if (type.has("nestedTypes")) {
            type.getAsJsonArray("nestedTypes").forEach(n -> nested.add(outlineType(n.getAsJsonObject())));
        }
        outline.add("nestedTypes", nested);
        return outline;
    }

    private static JsonArray keep(JsonArray items, String... keys) {
        JsonArray result = new JsonArray();
        if (items == null) {
            return result;
        }
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            JsonObject kept = new JsonObject();
            for (String key : keys) {
                if (item.has(key)) {
                    JsonElement value = item.get(key);
                    if (key.equals("parameters") && value.isJsonArray()) {
                        value = keep(value.getAsJsonArray(), "name", "type");
                    }
                    kept.add(key, value);
                }
            }
            result.add(kept);
        }
        return result;
    }
}
