// Mirrors the JSON written by pretty-docs-maven-plugin (DocumentationSite, schema version 1).
// Gson omits null fields, so anything that can be null in Java is optional here.

export const SUPPORTED_SCHEMA_VERSION = 2

export type ModifierKind =
  | "PUBLIC"
  | "PROTECTED"
  | "PRIVATE"
  | "PACKAGE_PRIVATE"
  | "STATIC"
  | "FINAL"
  | "ABSTRACT"
  | "SYNCHRONIZED"
  | "VOLATILE"
  | "TRANSIENT"
  | "NATIVE"
  | "STRICTFP"
  | "DEFAULT"
  | "SEALED"
  | "NON_SEALED"

// The last four are TypeScript declarations (pretty-ts): a function's overloads are its methods,
// a variable is one field, a namespace's declarations are its nested types.
export type TypeKind = "CLASS" | "INTERFACE" | "ENUM" | "ANNOTATION" | "RECORD" | "TYPE_ALIAS" | "FUNCTION" | "VARIABLE" | "NAMESPACE"

export interface Modifier {
  kind: ModifierKind
  position: number
}

export interface Annotation {
  name: string
  qualifiedName?: string
  values?: Record<string, string>
}

export interface TypeReference {
  name: string
  qualifiedName?: string
  typeArguments?: TypeReference[]
  isArray: boolean
  arrayDimensions: number
  isPrimitive: boolean
  isWildcard: boolean
  wildcardKind?: "UNBOUNDED" | "EXTENDS" | "SUPER"
  wildcardBound?: TypeReference
}

export interface TypeParameter {
  name: string
  bounds: TypeReference[]
}

export interface JavadocTag {
  name: string // "@param", "@return", ...
  text: string
  paramName?: string
  position: number
}

export interface Javadoc {
  description: string
  tags: JavadocTag[]
  startLine: number
  endLine: number
  /** Set in resolved mode when the doc was taken from an overridden method. */
  inheritedFrom?: string
}

export interface Comment {
  kind: "BLOCK" | "LINE" | "JAVADOC"
  content: string
  startLine: number
  endLine: number
}

export interface MemberReference {
  methodName: string
  declaringTypeName?: string
  qualifiedDeclaringTypeName?: string
  parameterTypes?: TypeReference[]
  line: number
}

export interface Parameter {
  name: string
  type: TypeReference
  annotations: Annotation[]
  isVarArgs: boolean
  isFinal: boolean
}

export interface Field {
  name: string
  type: TypeReference
  modifiers: Modifier[]
  annotations: Annotation[]
  initializer?: string
  javadoc?: Javadoc
  /** The declaration as written, for languages other than Java (TypeScript). */
  signature?: string
  readBy: MemberReference[]
  writtenBy: MemberReference[]
  startLine: number
  endLine: number
}

export interface Method {
  name: string
  returnType?: TypeReference
  modifiers: Modifier[]
  annotations: Annotation[]
  parameters: Parameter[]
  thrownExceptions: TypeReference[]
  typeParameters: TypeParameter[]
  body?: string
  javadoc?: Javadoc
  /** The declaration as written, for languages other than Java (TypeScript). */
  signature?: string
  calledBy: MemberReference[]
  calls: MemberReference[]
  cyclomaticComplexity: number
  lineCount: number
  startLine: number
  endLine: number
}

export interface Constructor {
  name: string
  modifiers: Modifier[]
  annotations: Annotation[]
  parameters: Parameter[]
  thrownExceptions: TypeReference[]
  typeParameters: TypeParameter[]
  body?: string
  javadoc?: Javadoc
  /** The declaration as written, for languages other than Java (TypeScript). */
  signature?: string
  calledBy: MemberReference[]
  startLine: number
  endLine: number
}

export interface EnumConstant {
  name: string
  arguments: string[]
  javadoc?: Javadoc
  ordinal: number
}

export interface RecordComponent {
  name: string
  type: TypeReference
  javadoc?: Javadoc
}

export interface TypeStructure {
  /** True in the outline docs-data.js holds: members carry names and signatures only. */
  outline?: boolean
  name: string
  qualifiedName: string
  kind: TypeKind
  modifiers: Modifier[]
  annotations: Annotation[]
  typeParameters: TypeParameter[]
  superclass?: { name: string; qualifiedName?: string }
  interfaces: { name: string; qualifiedName?: string }[]
  fields: Field[]
  methods: Method[]
  constructors: Constructor[]
  nestedTypes: TypeStructure[]
  javadoc?: Javadoc
  /** The declaration as written, for languages other than Java (TypeScript). */
  signature?: string
  enumConstants: EnumConstant[]
  usedBy: string[]
  recordComponents: RecordComponent[]
  /** Resolved mode only. */
  inheritedMethods?: MemberReference[]
  inheritedFields?: { fieldName: string; declaringTypeName?: string; qualifiedDeclaringTypeName?: string }[]
  startLine: number
  endLine: number
}

export interface JavaFile {
  fileName: string
  /** Absent for Java; "typescript" for files read by pretty-ts. */
  language?: string
  filePath?: string
  packageName: string
  types: TypeStructure[]
  fileComments: Comment[]
  totalLines: number
  codeLines: number
  commentLines: number
  blankLines: number
}

export interface Dependency {
  groupId: string
  artifactId: string
  version?: string
  scope: string
  type?: string
  classifier?: string
  optional: boolean
}

export interface MavenProject {
  groupId: string
  artifactId: string
  version: string
  packaging: string
  name?: string
  description?: string
  url?: string
  inceptionYear?: string
  organizationName?: string
  organizationUrl?: string
  parent?: { groupId: string; artifactId: string; version?: string }
  licenses: { name?: string; url?: string }[]
  developers: {
    id?: string
    name?: string
    email?: string
    url?: string
    organization?: string
    roles: string[]
  }[]
  scm?: { url?: string; connection?: string; developerConnection?: string; tag?: string }
  issueManagementUrl?: string
  ciManagementUrl?: string
  properties: Record<string, string>
  dependencies: Dependency[]
  managedDependencies: Dependency[]
  plugins: { groupId?: string; artifactId: string; version?: string; goals: string[] }[]
  modules: string[]
}

export interface ModuleStatistics {
  files: number
  packages: number
  types: number
  methods: number
  fields: number
  documented: number
  documentable: number
  linesOfCode: number
  totalLines?: number
  codeLines?: number
  commentLines?: number
  blankLines?: number
  typesByKind?: Partial<Record<TypeKind, number>>
  averageComplexity?: number
  maxComplexity?: number
}

/** An image found in a module directory. */
export interface Asset {
  /** Relative to the module directory. */
  path: string
  /** The copy in the site, relative to the site root. */
  href: string
  size: number
}

export interface ModuleDocumentation {
  id: string
  parentId?: string
  /** "typescript" for TypeScript packages and workspaces, which form a tree of their own; Java otherwise. */
  language?: "java" | "typescript"
  relativePath: string
  project: MavenProject
  files: JavaFile[]
  testFiles: JavaFile[]
  statistics: ModuleStatistics
  readmeHtml?: string
  /** Images in the module directory (icons, logos, public/ files), copied into the site. */
  assets?: Asset[]
  /** Script with the module's full sources; until it is loaded, files hold an outline (see lib/docs/module-data.ts). */
  dataScript?: string
}

export interface DocumentationSite {
  schemaVersion: number
  title?: string
  generator: string
  generatedAt: string
  visibility: string
  rootModuleId?: string
  /** Site path of the project logo. */
  logo?: string
  /** The source repository, e.g. on GitHub. */
  repositoryUrl?: string
  modules: ModuleDocumentation[]
  mode?: "resolved" | "source"
  externalDocs?: ExternalDocumentation[]
  spring?: SpringStructure
  diagrams?: DiagramStructure[]
  quality?: QualityStructure
}

export interface ExternalDocumentation {
  label: string
  baseUrl: string
  packages: Record<string, string> // package -> module ("" when not modular)
}

declare global {
  interface Window {
    __PRETTY_DOCS__?: DocumentationSite
    __PRETTY_DOCS_MODULES__?: Record<string, { files: JavaFile[]; testFiles: JavaFile[] }>
  }
}

// ---- Spring (pretty-spring), present only when the project uses Spring

export type BeanKind =
  | "COMPONENT"
  | "SERVICE"
  | "REPOSITORY"
  | "CONTROLLER"
  | "REST_CONTROLLER"
  | "CONTROLLER_ADVICE"
  | "CONFIGURATION"
  | "BEAN_METHOD"
  | "DATA_REPOSITORY"
  | "CONFIGURATION_PROPERTIES"

export interface InjectionPoint {
  kind: "CONSTRUCTOR" | "FIELD" | "SETTER" | "BEAN_METHOD_PARAMETER"
  name: string
  typeName: string
  collection: boolean
  required: boolean
  qualifier?: string
  candidates: string[]
  resolvedTo?: string
}

export interface BeanDefinition {
  name: string
  aliases: string[]
  kind: BeanKind
  typeName: string
  assignableTo: string[]
  declaringType: string
  declaringMember?: string
  scope: string
  primary: boolean
  lazy: boolean
  qualifiers: string[]
  profiles: string[]
  conditions: string[]
  dependencies: InjectionPoint[]
  injectedInto: string[]
  inComponentScan: boolean
  line: number
  autoConfigured?: boolean
  applications?: string[] // contexts that include it; empty when not worked out per application
  notes?: string[] // why other applications leave it out, or why it backs off
}

export interface EndpointStructure {
  httpMethods: string[]
  path: string
  controllerType: string
  methodName: string
  methodId: string
  returnType: string
  produces: string[]
  consumes: string[]
  parameters: { source: string; name: string; typeName: string; required: boolean; defaultValue?: string }[]
  responseBody: boolean
}

export interface BeanIssue {
  severity: "ERROR" | "WARNING" | "INFO"
  kind: string
  message: string
  beanName?: string
  typeName?: string
  applications?: string[] // contexts it occurs in; empty when it does not depend on one
  file?: string
  line?: number
}

export interface SpringApplication {
  typeName: string
  hasMainMethod: boolean
  scanPackages: string[]
  excludedAutoConfigurations: string[]
  moduleId?: string
  modules?: string[]
  activeProfiles?: string[]
  autoConfigurations?: string[]
  beanCount?: number
}

export interface PropertyUsage {
  key: string
  defaultValue?: string
  expression: string
  typeName: string
  memberName?: string
  source?: "@Value" | "@ConditionalOnProperty" | "Environment"
  required?: boolean
}

export interface ConfigurationEntry {
  key: string
  value: string
  line: number
  profile?: string
  comment?: string
  environmentVariable?: string
  placeholderDefault?: string
}

export interface ConfigurationFile {
  moduleId: string
  path: string
  fileName: string
  format: "yaml" | "properties"
  profile?: string
  content: string
  entries: ConfigurationEntry[]
}

export type ConfigurationKeyStatus = "PROJECT" | "LIBRARY" | "UNKNOWN" | "UNCHECKED"

export interface ConfigurationKey {
  key: string
  status: ConfigurationKeyStatus
  description?: string
  typeName?: string
  defaultValue?: string
  deprecated: boolean
  boundTo: string[]
  values: { moduleId: string; path: string; profile?: string; line: number; value: string }[]
  suggestion?: string
}

export interface SpringStructure {
  applications: SpringApplication[]
  scanPackages: string[]
  beans: BeanDefinition[]
  endpoints: EndpointStructure[]
  configurationProperties: {
    prefix: string
    typeName: string
    registered: boolean
    properties: { key: string; typeName: string; defaultValue?: string; description?: string }[]
  }[]
  propertyUsages: PropertyUsage[]
  entities: { typeName: string; entityName: string; tableName?: string; idField?: string }[]
  configurationFiles?: ConfigurationFile[]
  configurationKeys?: ConfigurationKey[]
  issues: BeanIssue[]
}

// ---- Code quality (pretty-quality and the TypeScript compiler), present when the checks ran

export type QualityPriority = "HIGH" | "MEDIUM" | "LOW"

export interface QualityFinding {
  tool: string // PMD, SpotBugs, TypeScript
  rule: string
  category: string
  priority: QualityPriority
  message: string
  moduleId?: string
  file?: string // relative to the project root
  line: number
  endLine: number
  typeName?: string
  memberName?: string
  url?: string
}

export interface QualityToolRun {
  name: string
  version?: string
  ran: boolean
  note?: string
  findings: number
}

export interface QualityStructure {
  tools: QualityToolRun[]
  findings: QualityFinding[]
}

export interface DiagramStructure {
  id: string
  kind: "PACKAGE_CLASSES" | "MODULE_DEPENDENCIES" | "SPRING_BEANS"
  title: string
  moduleId?: string
  packageName?: string
  pumlPath: string
  svgPath?: string // missing when rendering failed or was disabled
  elementCount: number
}
