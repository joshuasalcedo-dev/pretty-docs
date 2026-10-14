import type {
  Constructor,
  DocumentationSite,
  Javadoc,
  JavaFile,
  Method,
  Modifier,
  ModuleDocumentation,
  TypeKind,
  TypeParameter,
  TypeReference,
  TypeStructure,
} from "./types"

export interface IndexedType {
  type: TypeStructure
  module: ModuleDocumentation
  file: JavaFile
  packageName: string
  /** Enclosing type for nested types. */
  outer?: IndexedType
}

export interface PackageInfo {
  name: string
  module: ModuleDocumentation
  types: IndexedType[] // top-level types only
  description?: string // from package-info.java
}

export interface SearchEntry {
  kind: "module" | "package" | "type" | "method" | "field" | "bean" | "endpoint"
  label: string
  detail: string
  href: string
  keywords: string
}

export interface DocsIndex {
  site: DocumentationSite
  root?: ModuleDocumentation
  modules: Map<string, ModuleDocumentation>
  children: Map<string | undefined, ModuleDocumentation[]>
  packages: Map<string, PackageInfo[]> // module id -> packages, sorted
  types: Map<string, IndexedType> // qualified name -> type
  search: SearchEntry[]
  external: Map<string, ExternalPackage> // package -> where its javadoc lives
}

export interface ExternalPackage {
  baseUrl: string
  module: string
  label: string
}

export const routes = {
  home: () => "#/",
  module: (moduleId: string) => `#/m/${encodeURIComponent(moduleId)}`,
  pkg: (moduleId: string, pkg: string) =>
    `#/m/${encodeURIComponent(moduleId)}/p/${encodeURIComponent(pkg || "(default)")}`,
  stats: () => "#/stats",
  spring: (tab?: string) => `#/spring${tab ? `/${tab}` : ""}`,
  type: (qualifiedName: string, member?: string) =>
    `#/t/${encodeURIComponent(qualifiedName)}${member ? `/${encodeURIComponent(member)}` : ""}`,
}

export function buildIndex(site: DocumentationSite): DocsIndex {
  const modules = new Map(site.modules.map((m) => [m.id, m]))
  const children = new Map<string | undefined, ModuleDocumentation[]>()
  for (const module of site.modules) {
    if (module.id === site.rootModuleId) continue
    // Modules whose parent was excluded hang off the root.
    const parent = module.parentId && modules.has(module.parentId) ? module.parentId : site.rootModuleId
    children.set(parent, [...(children.get(parent) ?? []), module])
  }

  const packages = new Map<string, PackageInfo[]>()
  const types = new Map<string, IndexedType>()
  const search: SearchEntry[] = []

  for (const module of site.modules) {
    const name = moduleName(module)
    search.push({
      kind: "module",
      label: name,
      detail: module.id,
      href: routes.module(module.id),
      keywords: `${name} ${module.id}`.toLowerCase(),
    })

    const byPackage = new Map<string, PackageInfo>()
    for (const file of module.files) {
      const pkg = byPackage.get(file.packageName) ?? {
        name: file.packageName,
        module,
        types: [],
      }
      byPackage.set(file.packageName, pkg)
      if (file.fileName === "package-info.java") {
        const doc = file.fileComments.find((c) => c.kind === "JAVADOC")
        if (doc) pkg.description = doc.content
      }
      for (const type of file.types) {
        const indexed = indexType(type, module, file, undefined, types, search)
        pkg.types.push(indexed)
      }
    }
    const sorted = [...byPackage.values()]
      .filter((p) => p.types.length > 0 || p.description)
      .sort((a, b) => a.name.localeCompare(b.name))
    sorted.forEach((p) => {
      p.types.sort((a, b) => a.type.name.localeCompare(b.type.name))
      search.push({
        kind: "package",
        label: p.name || "(default package)",
        detail: name,
        href: routes.pkg(module.id, p.name),
        keywords: p.name.toLowerCase(),
      })
    })
    packages.set(module.id, sorted)
  }

  for (const bean of site.spring?.beans ?? []) {
    search.push({
      kind: "bean",
      label: bean.name,
      detail: bean.typeName.split(".").pop() ?? bean.typeName,
      href: types.has(bean.declaringType) ? routes.type(bean.declaringType, bean.declaringMember) : routes.spring("beans"),
      keywords: `${bean.name} ${bean.typeName}`.toLowerCase(),
    })
  }
  for (const endpoint of site.spring?.endpoints ?? []) {
    const label = `${endpoint.httpMethods.join(",") || "ANY"} ${endpoint.path}`
    search.push({
      kind: "endpoint",
      label,
      detail: `${endpoint.controllerType.split(".").pop()}.${endpoint.methodName}`,
      href: routes.type(endpoint.controllerType, endpoint.methodId),
      keywords: `${label} ${endpoint.path}`.toLowerCase(),
    })
  }

  return {
    site,
    root: site.rootModuleId ? modules.get(site.rootModuleId) : site.modules[0],
    modules,
    children,
    packages,
    types,
    search,
    external: externalPackages(site),
  }
}

function externalPackages(site: DocumentationSite) {
  const external = new Map<string, ExternalPackage>()
  for (const docs of site.externalDocs ?? []) {
    for (const [pkg, module] of Object.entries(docs.packages)) {
      if (!external.has(pkg)) external.set(pkg, { baseUrl: docs.baseUrl, module, label: docs.label })
    }
  }
  return external
}

/**
 * Javadoc URL for a type outside the project, like javadoc -link: the longest known package prefix,
 * then Outer.Inner.html. Member anchors use the javadoc 9+ form name(full.Type,...).
 */
export function externalTypeUrl(index: DocsIndex, qualifiedName: string, member?: string) {
  const parts = qualifiedName.split(".")
  for (let i = parts.length - 1; i > 0; i--) {
    const pkg = parts.slice(0, i).join(".")
    const location = index.external.get(pkg)
    if (location && /^[A-Z]/.test(parts[i])) {
      const page = `${location.module ? `${location.module}/` : ""}${pkg.replaceAll(".", "/")}/${parts.slice(i).join(".")}.html`
      return { url: location.baseUrl + page + (member ? `#${member}` : ""), label: location.label }
    }
  }
  return undefined
}

function indexType(
  type: TypeStructure,
  module: ModuleDocumentation,
  file: JavaFile,
  outer: IndexedType | undefined,
  types: Map<string, IndexedType>,
  search: SearchEntry[]
): IndexedType {
  const indexed: IndexedType = { type, module, file, packageName: file.packageName, outer }
  types.set(type.qualifiedName, indexed)
  search.push({
    kind: "type",
    label: displayName(indexed),
    detail: file.packageName,
    href: routes.type(type.qualifiedName),
    keywords: `${type.name} ${type.qualifiedName}`.toLowerCase(),
  })
  for (const method of type.methods) {
    search.push({
      kind: "method",
      label: `${type.name}.${method.name}(${method.parameters.map((p) => typeToString(p.type)).join(", ")})`,
      detail: file.packageName,
      href: routes.type(type.qualifiedName, memberId(method)),
      keywords: `${method.name} ${type.name}.${method.name}`.toLowerCase(),
    })
  }
  for (const field of type.fields) {
    search.push({
      kind: "field",
      label: `${type.name}.${field.name}`,
      detail: file.packageName,
      href: routes.type(type.qualifiedName, field.name),
      keywords: `${field.name} ${type.name}.${field.name}`.toLowerCase(),
    })
  }
  type.nestedTypes.forEach((nested) => indexType(nested, module, file, indexed, types, search))
  return indexed
}

// ------------------------------------------------------------------ text

export function moduleName(module: ModuleDocumentation) {
  return module.project.name || module.project.artifactId
}

/** Outer.Inner for nested types. */
export function displayName(indexed: IndexedType): string {
  return indexed.outer ? `${displayName(indexed.outer)}.${indexed.type.name}` : indexed.type.name
}

/** First sentence of a javadoc description, without markup. */
export function summary(doc?: Javadoc): string {
  if (!doc?.description) return ""
  const text = stripMarkup(doc.description)
  const match = text.match(/^(.*?[.!?])(\s|$)/s)
  return (match ? match[1] : text).trim()
}

export function stripMarkup(text: string): string {
  return text
    .replace(/\{@(?:code|literal)\s+([^}]*)}/g, "$1")
    .replace(/\{@(?:link|linkplain)\s+([^}\s]+)(?:\s+([^}]*))?}/g, (_, ref: string, label?: string) =>
      label?.trim() || ref.replace(/^#/, "")
    )
    .replace(/\{@\w+\s*([^}]*)}/g, "$1")
    .replace(/<[^>]+>/g, "")
    .replace(/\s+/g, " ")
    .trim()
}

export function tags(doc: Javadoc | undefined, name: string) {
  return doc?.tags.filter((t) => t.name === name) ?? []
}

export function isDeprecated(element: { javadoc?: Javadoc; annotations: { name: string }[] }) {
  return element.annotations.some((a) => a.name === "Deprecated") || tags(element.javadoc, "@deprecated").length > 0
}

// ------------------------------------------------------------- signatures

export function typeToString(ref: TypeReference): string {
  let text: string
  if (ref.isWildcard) {
    text =
      ref.wildcardKind === "EXTENDS" && ref.wildcardBound
        ? `? extends ${typeToString(ref.wildcardBound)}`
        : ref.wildcardKind === "SUPER" && ref.wildcardBound
          ? `? super ${typeToString(ref.wildcardBound)}`
          : "?"
  } else {
    text = ref.name
    if (ref.typeArguments?.length) text += `<${ref.typeArguments.map(typeToString).join(", ")}>`
  }
  return text + "[]".repeat(ref.isArray ? ref.arrayDimensions : 0)
}

export function typeParametersToString(params: TypeParameter[]) {
  if (!params.length) return ""
  return `<${params
    .map((p) => (p.bounds.length ? `${p.name} extends ${p.bounds.map(typeToString).join(" & ")}` : p.name))
    .join(", ")}>`
}

const MODIFIER_ORDER = [
  "PUBLIC",
  "PROTECTED",
  "PRIVATE",
  "ABSTRACT",
  "DEFAULT",
  "STATIC",
  "SEALED",
  "NON_SEALED",
  "FINAL",
  "TRANSIENT",
  "VOLATILE",
  "SYNCHRONIZED",
  "NATIVE",
  "STRICTFP",
]

export function modifiersToString(modifiers: Modifier[]) {
  return modifiers
    .filter((m) => m.kind !== "PACKAGE_PRIVATE")
    .sort((a, b) => MODIFIER_ORDER.indexOf(a.kind) - MODIFIER_ORDER.indexOf(b.kind))
    .map((m) => m.kind.toLowerCase().replace("_", "-"))
    .join(" ")
}

export function hasModifier(modifiers: Modifier[], kind: Modifier["kind"]) {
  return modifiers.some((m) => m.kind === kind)
}

export function visibilityOf(modifiers: Modifier[]): "public" | "protected" | "package" | "private" {
  if (hasModifier(modifiers, "PRIVATE")) return "private"
  if (hasModifier(modifiers, "PROTECTED")) return "protected"
  if (hasModifier(modifiers, "PACKAGE_PRIVATE")) return "package"
  return "public"
}

/** Stable anchor for a method or constructor: name(Type,Type). */
export function memberId(member: Method | Constructor) {
  return `${member.name}(${member.parameters.map((p) => typeToString(p.type)).join(",")})`
}

export const KIND_LABELS: Record<TypeKind, { singular: string; plural: string }> = {
  INTERFACE: { singular: "Interface", plural: "Interfaces" },
  CLASS: { singular: "Class", plural: "Classes" },
  ENUM: { singular: "Enum", plural: "Enums" },
  RECORD: { singular: "Record", plural: "Records" },
  ANNOTATION: { singular: "Annotation", plural: "Annotations" },
}

export const KIND_ORDER: TypeKind[] = ["INTERFACE", "CLASS", "RECORD", "ENUM", "ANNOTATION"]

export function kindLabel(type: TypeStructure) {
  if (type.kind === "CLASS" && hasModifier(type.modifiers, "ABSTRACT")) return "Abstract class"
  return KIND_LABELS[type.kind].singular
}

/** Percentage as text; "—" when there is nothing to document. */
export function coverage(documented: number, documentable: number) {
  return documentable === 0 ? "—" : `${Math.round((documented / documentable) * 100)}%`
}

export function formatNumber(value: number) {
  return new Intl.NumberFormat().format(value)
}

/**
 * Short sidebar labels: the part after the package prefix all of a module's packages share,
 * e.g. com.acme.app, com.acme.app.web -> "app", "app.web".
 */
export function shortPackageNames(names: string[]): Map<string, string> {
  const split = names.filter(Boolean).map((n) => n.split("."))
  let common = 0
  if (split.length > 0) {
    const shortest = Math.min(...split.map((s) => s.length))
    while (common < shortest - 1 && split.every((s) => s[common] === split[0][common])) common++
  }
  return new Map(names.map((n) => [n, n ? n.split(".").slice(common).join(".") : "(default)"]))
}
