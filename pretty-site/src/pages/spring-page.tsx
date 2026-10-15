import { AlertTriangleIcon, ChevronRightIcon, CircleAlertIcon, InfoIcon, SearchIcon } from "lucide-react"
import { useMemo, useState } from "react"

import { DiagramView, diagramsFor } from "@/components/docs/diagram-view"
import { JavaCode } from "@/components/docs/java-code"
import { TypeNameLink } from "@/components/docs/type-ref"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useDocs } from "@/lib/docs/context"
import { routes } from "@/lib/docs/model"
import { scrollToElement } from "@/lib/scroll"
import type {
  BeanDefinition,
  BeanIssue,
  BeanKind,
  ConfigurationFile,
  ConfigurationKey,
  ConfigurationKeyStatus,
  SpringApplication,
  SpringStructure,
} from "@/lib/docs/types"

const KIND_LABEL: Record<BeanKind, string> = {
  COMPONENT: "Component",
  SERVICE: "Service",
  REPOSITORY: "Repository",
  CONTROLLER: "Controller",
  REST_CONTROLLER: "REST controller",
  CONTROLLER_ADVICE: "Controller advice",
  CONFIGURATION: "Configuration",
  BEAN_METHOD: "@Bean",
  DATA_REPOSITORY: "Spring Data",
  CONFIGURATION_PROPERTIES: "Properties",
}

const TABS = ["issues", "beans", "graph", "endpoints", "configuration"] as const
const ALL = "*"

const simple = (name: string) => name.split(".").pop()!

/** Whether something tagged with application contexts belongs to the selected one (untagged things belong to all). */
function inApplication(applications: string[] | undefined, selected: string, strict: boolean) {
  if (selected === ALL) return true
  if (!applications?.length) return !strict
  return applications.includes(selected)
}

export function SpringPage({ tab }: { tab?: string }) {
  const { site } = useDocs()
  const spring = site.spring
  const [selected, setSelected] = useState(ALL)
  const view = useMemo(() => (spring ? filtered(spring, selected) : null), [spring, selected])
  if (!spring || !view) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>No Spring usage found</EmptyTitle>
          <EmptyDescription>This project does not declare Spring beans.</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }
  const graph = diagramsFor(site, "SPRING_BEANS")[0]
  const errors = view.issues.filter((i) => i.severity === "ERROR").length
  const warnings = view.issues.filter((i) => i.severity === "WARNING").length
  const active = TABS.includes(tab as (typeof TABS)[number]) ? tab! : errors + warnings > 0 ? "issues" : "beans"
  const perApplication = spring.applications.some((a) => a.beanCount)

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <Badge variant="secondary" className="w-fit">Spring</Badge>
        <h1 className="font-heading text-4xl font-bold tracking-tight">Application wiring</h1>
        <p className="max-w-3xl font-serif text-lg leading-8">
          {perApplication
            ? "Beans, dependencies and endpoints found by static analysis of the sources. Each application gets its own context: its module and the modules it depends on, what it scans, imports and auto-configures, with profiles, @ConditionalOnProperty and @ConditionalOn(Missing)Bean evaluated against its configuration files. Other conditions are assumed to hold, and library auto-configurations are not read, so dependencies on library types are listed as information."
            : "Beans, dependencies and endpoints found by static analysis of the sources. Auto-configuration, conditions and profiles are not evaluated, so dependencies on library types are listed as information."}
        </p>
      </header>

      {spring.applications.length > 0 && (
        <Applications applications={spring.applications} selected={selected} onSelect={setSelected} perApplication={perApplication} />
      )}

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Beans" value={view.beans.length} />
        <Stat label="Endpoints" value={view.endpoints.length} />
        <Stat label="Errors" value={errors} />
        <Stat label="Warnings" value={warnings} />
      </div>

      <Tabs value={active} onValueChange={(value) => (window.location.hash = routes.spring(String(value)))}>
        <TabsList variant="pill">
          <TabsTrigger value="issues">Issues ({view.issues.length})</TabsTrigger>
          <TabsTrigger value="beans">Beans</TabsTrigger>
          {graph && <TabsTrigger value="graph">Graph</TabsTrigger>}
          <TabsTrigger value="endpoints">Endpoints</TabsTrigger>
          <TabsTrigger value="configuration">Configuration</TabsTrigger>
        </TabsList>
        <TabsContent value="issues" className="pt-4">
          <Issues issues={view.issues} />
        </TabsContent>
        <TabsContent value="beans" className="pt-4">
          <Beans beans={view.beans} />
        </TabsContent>
        {graph && (
          <TabsContent value="graph" className="pt-4">
            <DiagramView diagram={graph} />
          </TabsContent>
        )}
        <TabsContent value="endpoints" className="pt-4">
          <Endpoints endpoints={view.endpoints} />
        </TabsContent>
        <TabsContent value="configuration" className="pt-4">
          <Configuration spring={spring} files={view.files} keys={view.keys} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

/** The page's content for one application context, or for all of them. */
function filtered(spring: SpringStructure, selected: string) {
  const beans = spring.beans.filter((b) => inApplication(b.applications, selected, true))
  const controllers = new Set(beans.map((b) => b.typeName))
  const app = spring.applications.find((a) => a.typeName === selected)
  const files = (spring.configurationFiles ?? []).filter((f) => !app?.moduleId || f.moduleId === app.moduleId)
  const paths = new Set(files.map((f) => f.path))
  return {
    beans,
    issues: spring.issues.filter((i) => inApplication(i.applications, selected, false) && (!i.file || paths.has(i.file))),
    endpoints: selected === ALL ? spring.endpoints : spring.endpoints.filter((e) => controllers.has(e.controllerType)),
    files,
    keys: (spring.configurationKeys ?? [])
      .map((k) => ({ ...k, values: k.values.filter((v) => paths.has(v.path)) }))
      .filter((k) => k.values.length > 0),
  }
}

function Applications({ applications, selected, onSelect, perApplication }: {
  applications: SpringApplication[]
  selected: string
  onSelect: (typeName: string) => void
  perApplication: boolean
}) {
  const app = applications.find((a) => a.typeName === selected)
  return (
    <section className="flex flex-col gap-3">
      {perApplication && applications.length > 1 && (
        <ToggleGroup value={[selected]} onValueChange={(value) => value[0] && onSelect(String(value[0]))} variant="outline" size="sm" className="flex-wrap">
          <ToggleGroupItem value={ALL}>All applications</ToggleGroupItem>
          {applications.map((a) => (
            <ToggleGroupItem key={a.typeName} value={a.typeName}>{simple(a.typeName)}</ToggleGroupItem>
          ))}
        </ToggleGroup>
      )}
      {(app ? [app] : applications).map((a) => (
        <div key={a.typeName} className="flex flex-col gap-1 text-sm">
          <p className="flex flex-wrap items-baseline gap-x-1.5">
            <span className="text-muted-foreground">Application</span>
            <TypeNameLink name={simple(a.typeName)} qualifiedName={a.typeName} />
            {a.moduleId && <span className="text-muted-foreground">in {a.moduleId.split(":").pop()}</span>}
            {a.beanCount ? <span className="text-muted-foreground">· {a.beanCount} beans</span> : null}
            <span className="text-muted-foreground">· scans</span>
            <code className="font-mono text-xs">{a.scanPackages.join(", ")}</code>
            {a.activeProfiles && a.activeProfiles.length > 0 && a.activeProfiles.map((p) => <Badge key={p} variant="outline">profile: {p}</Badge>)}
          </p>
          {app && (a.autoConfigurations?.length ?? 0) > 0 && (
            <p className="text-xs text-muted-foreground">
              Auto-configured by {a.autoConfigurations!.map((c, i) => (
                <span key={c}>
                  {i > 0 && ", "}
                  <TypeNameLink name={simple(c)} qualifiedName={c} />
                </span>
              ))}
            </p>
          )}
          {app && (a.modules?.length ?? 0) > 1 && (
            <p className="text-xs text-muted-foreground">On its classpath from this build: {a.modules!.map((m) => m.split(":").pop()).join(", ")}</p>
          )}
        </div>
      ))}
    </section>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums">{value}</CardTitle>
      </CardHeader>
    </Card>
  )
}

const SEVERITY = {
  ERROR: { icon: CircleAlertIcon, title: "Error", variant: "destructive" as const },
  WARNING: { icon: AlertTriangleIcon, title: "Warning", variant: "default" as const },
  INFO: { icon: InfoIcon, title: "Note", variant: "default" as const },
}

function Issues({ issues }: { issues: BeanIssue[] }) {
  const [shown, setShown] = useState<string[]>(["ERROR", "WARNING"])
  if (issues.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>No wiring problems found</EmptyTitle>
          <EmptyDescription>Every injection point resolves to exactly one bean.</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }
  const visible = issues.filter((i) => shown.includes(i.severity))
  return (
    <div className="flex flex-col gap-3">
      <ToggleGroup multiple value={shown} onValueChange={(value) => setShown(value as string[])} variant="outline" size="sm">
        {(["ERROR", "WARNING", "INFO"] as const).map((s) => (
          <ToggleGroupItem key={s} value={s}>
            {SEVERITY[s].title}s ({issues.filter((i) => i.severity === s).length})
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {visible.map((issue, i) => {
        const severity = SEVERITY[issue.severity]
        return (
          <Alert key={i} variant={severity.variant}>
            <severity.icon />
            <AlertTitle className="flex flex-wrap items-center gap-2">
              {severity.title}
              <Badge variant="outline" className="font-mono">{issue.kind.toLowerCase().replaceAll("_", " ")}</Badge>
              {issue.applications?.map((a) => <Badge key={a} variant="secondary">{simple(a)}</Badge>)}
            </AlertTitle>
            <AlertDescription>
              <p>{issue.message}</p>
              {issue.typeName && (
                <p className="text-xs">
                  See <TypeNameLink name={simple(issue.typeName)} qualifiedName={issue.typeName} />
                </p>
              )}
              {issue.file && (
                <p className="text-xs">
                  See <FileLink path={issue.file} line={issue.line} />
                </p>
              )}
            </AlertDescription>
          </Alert>
        )
      })}
    </div>
  )
}

function Beans({ beans: all }: { beans: BeanDefinition[] }) {
  const [query, setQuery] = useState("")
  const beans = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? all.filter((b) => `${b.name} ${b.typeName} ${b.kind}`.toLowerCase().includes(q)) : all
  }, [all, query])

  return (
    <div className="flex flex-col gap-4">
      <InputGroup className="max-w-sm">
        <InputGroupInput placeholder="Filter beans…" value={query} onChange={(e) => setQuery(e.target.value)} />
        <InputGroupAddon>
          <SearchIcon />
        </InputGroupAddon>
      </InputGroup>
      <div className="grid gap-3 lg:grid-cols-2">
        {beans.map((bean) => <BeanCard key={bean.name + bean.declaringType + (bean.declaringMember ?? "")} bean={bean} />)}
      </div>
    </div>
  )
}

const beanAnchor = (bean: BeanDefinition) =>
  `bean-${bean.declaringType}-${bean.declaringMember ?? ""}-${bean.name}`.replace(/[^\w-]/g, "-")

/** One bean: what it is, what it needs and who needs it. Also used on type pages. */
export function BeanCard({ bean }: { bean: BeanDefinition }) {
  const index = useDocs()
  // Bean names repeat across applications (each may define its own), so link to the one in a shared context.
  const target = (name: string) =>
    index.site.spring?.beans.find((b) => b.name === name
      && (!bean.applications?.length || !b.applications?.length || b.applications.some((a) => bean.applications!.includes(a))))
  return (
    <div id={beanAnchor(bean)} className="flex flex-col gap-3 rounded-xl border p-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono font-semibold">{bean.name}</span>
        <Badge variant="secondary">{KIND_LABEL[bean.kind]}</Badge>
        {bean.autoConfigured && <Badge variant="outline">auto-configured</Badge>}
        {bean.scope !== "singleton" && <Badge variant="outline">{bean.scope}</Badge>}
        {bean.primary && <Badge variant="outline">primary</Badge>}
        {bean.lazy && <Badge variant="outline">lazy</Badge>}
        {bean.profiles.map((p) => <Badge key={p} variant="outline">profile: {p}</Badge>)}
        {!bean.inComponentScan && <Badge variant="destructive">not scanned</Badge>}
      </div>
      <p className="text-muted-foreground">
        <TypeNameLink name={simple(bean.typeName)} qualifiedName={bean.typeName} />
        {bean.declaringMember && (
          <>
            {" from "}
            <TypeNameLink name={simple(bean.declaringType)} qualifiedName={bean.declaringType} />
            <code className="font-mono">.{bean.declaringMember}()</code>
          </>
        )}
      </p>
      {bean.conditions.length > 0 && (
        <p className="font-mono text-xs text-muted-foreground">{bean.conditions.join(" ")}</p>
      )}
      {bean.dependencies.length > 0 && (
        <div className="flex flex-col gap-1">
          <p className="text-xs font-medium text-muted-foreground">Injects</p>
          {bean.dependencies.map((d) => (
            <p key={d.kind + d.name} className="flex flex-wrap items-baseline gap-x-2 font-mono text-xs">
              <span>{d.name}</span>
              <span className="text-muted-foreground">
                : {d.collection ? "many " : ""}
                <TypeNameLink name={simple(d.typeName)} qualifiedName={d.typeName} />
              </span>
              <span className="text-muted-foreground">→</span>
              {d.resolvedTo ? (
                <a
                  href={`${routes.spring("beans")}`}
                  onClick={() => {
                    const resolved = target(d.resolvedTo!)
                    if (resolved) setTimeout(() => scrollToElement(document.getElementById(beanAnchor(resolved))))
                  }}
                  className="text-primary underline-offset-4 hover:underline"
                >
                  {d.resolvedTo}
                </a>
              ) : d.collection ? (
                <span>{d.candidates.length} bean(s)</span>
              ) : d.candidates.length > 1 ? (
                <Badge variant="destructive">ambiguous ({d.candidates.length})</Badge>
              ) : !d.required ? (
                <span className="text-muted-foreground italic">optional</span>
              ) : index.types.has(d.typeName) ? (
                <Badge variant="destructive">missing bean</Badge>
              ) : (
                <span className="text-muted-foreground italic">from a library / auto-configuration</span>
              )}
              <span className="text-[10px] text-muted-foreground uppercase">{d.kind.toLowerCase().replaceAll("_", " ")}</span>
            </p>
          ))}
        </div>
      )}
      {bean.injectedInto.length > 0 && (
        <p className="text-xs text-muted-foreground">Injected into {bean.injectedInto.join(", ")}</p>
      )}
      {bean.applications && bean.applications.length > 0 && (
        <p className="text-xs text-muted-foreground">In {bean.applications.map(simple).join(", ")}</p>
      )}
      {bean.notes && bean.notes.length > 0 && (
        <ul className="flex flex-col gap-0.5 text-xs text-muted-foreground">
          {bean.notes.map((note) => <li key={note}>Not in {note}</li>)}
        </ul>
      )}
    </div>
  )
}

const METHOD_VARIANT: Record<string, "default" | "secondary" | "outline" | "destructive"> = {
  GET: "secondary",
  POST: "default",
  PUT: "outline",
  PATCH: "outline",
  DELETE: "destructive",
}

function Endpoints({ endpoints }: { endpoints: SpringStructure["endpoints"] }) {
  if (endpoints.length === 0) return <p className="text-sm text-muted-foreground">No request mappings found.</p>
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-28">Method</TableHead>
          <TableHead>Path</TableHead>
          <TableHead>Handler</TableHead>
          <TableHead>Parameters</TableHead>
          <TableHead>Returns</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {endpoints.map((e, i) => (
          <TableRow key={i}>
            <TableCell className="flex flex-wrap gap-1">
              {(e.httpMethods.length ? e.httpMethods : ["ANY"]).map((m) => (
                <Badge key={m} variant={METHOD_VARIANT[m] ?? "outline"} className="font-mono">{m}</Badge>
              ))}
            </TableCell>
            <TableCell className="font-mono text-sm">{e.path}</TableCell>
            <TableCell className="font-mono text-xs">
              <a href={routes.type(e.controllerType, e.methodId)} className="text-primary underline-offset-4 hover:underline">
                {simple(e.controllerType)}.{e.methodName}
              </a>
            </TableCell>
            <TableCell className="font-mono text-xs whitespace-normal">
              {e.parameters.map((p) => (
                <div key={p.source + p.name}>
                  <span className="text-muted-foreground">{p.source} </span>
                  {p.name}
                  {!p.required && "?"}
                  <span className="text-muted-foreground">: {p.typeName}</span>
                  {p.defaultValue !== undefined && <span className="text-muted-foreground"> = {p.defaultValue}</span>}
                </div>
              ))}
              {e.consumes.length > 0 && <div className="text-muted-foreground">consumes {e.consumes.join(", ")}</div>}
            </TableCell>
            <TableCell className="font-mono text-xs">{e.returnType}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

// ---- configuration

const fileAnchor = (path: string) => `config-${path.replace(/[^\w-]/g, "-")}`

function FileLink({ path, line, moduleId }: { path: string; line?: number; moduleId?: string }) {
  return (
    <a
      href={routes.spring("configuration")}
      onClick={() => setTimeout(() => scrollToElement(document.getElementById(fileAnchor(path))))}
      className="font-mono text-primary underline-offset-4 hover:underline"
    >
      {moduleId ? `${moduleId.split(":").pop()}/` : ""}
      {path.split("/").pop()}
      {line ? `:${line}` : ""}
    </a>
  )
}

const STATUS: Record<ConfigurationKeyStatus, { label: string; variant: "secondary" | "outline" | "destructive"; title: string }> = {
  PROJECT: { label: "project", variant: "secondary", title: "Read by this project" },
  LIBRARY: { label: "library", variant: "outline", title: "Documented by a library" },
  UNKNOWN: { label: "unknown", variant: "destructive", title: "Nothing known reads it" },
  UNCHECKED: { label: "unchecked", variant: "outline", title: "No library metadata to check it against" },
}

function Configuration({ spring, files, keys }: { spring: SpringStructure; files: ConfigurationFile[]; keys: ConfigurationKey[] }) {
  const empty = keys.length === 0 && files.length === 0 && spring.configurationProperties.length === 0
    && spring.propertyUsages.length === 0 && spring.entities.length === 0
  return (
    <div className="flex flex-col gap-10">
      {keys.length > 0 && <ConfigurationKeys keys={keys} />}
      {files.length > 0 && (
        <section className="flex flex-col gap-3">
          <h2 className="font-heading text-xl font-semibold">Files</h2>
          {files.map((file) => <ConfigurationFileView key={file.path} file={file} />)}
        </section>
      )}
      {(spring.configurationProperties.length > 0 || spring.propertyUsages.length > 0) && (
        <section className="flex flex-col gap-6">
          <h2 className="font-heading text-xl font-semibold">What the code reads</h2>
          {spring.configurationProperties.map((group) => (
            <section key={group.typeName + group.prefix} className="flex flex-col gap-2">
              <h3 className="flex flex-wrap items-center gap-2 text-sm font-medium">
                <code className="font-mono">{group.prefix}</code>
                <span className="text-muted-foreground">bound to</span>
                <TypeNameLink name={simple(group.typeName)} qualifiedName={group.typeName} />
                {!group.registered && <Badge variant="destructive">not registered</Badge>}
              </h3>
              <PropertyTable rows={group.properties.map((p) => ({ key: p.key, type: p.typeName, value: p.defaultValue, note: p.description }))} />
            </section>
          ))}
          {spring.propertyUsages.length > 0 && (
            <section className="flex flex-col gap-2">
              <h3 className="text-sm font-medium">@Value, @ConditionalOnProperty and Environment reads</h3>
              <PropertyTable
                rows={spring.propertyUsages.map((u) => ({
                  key: u.key,
                  type: `${u.source ?? "@Value"} in ${simple(u.typeName)}${u.memberName ? `.${u.memberName}` : ""}`,
                  value: u.defaultValue,
                  note: u.required ? "Required: startup fails when it is not set" : undefined,
                }))}
                typeLabel="Read by"
              />
            </section>
          )}
        </section>
      )}
      {spring.entities.length > 0 && (
        <section className="flex flex-col gap-2">
          <h2 className="font-heading text-xl font-semibold">JPA entities</h2>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Entity</TableHead>
                <TableHead>Table</TableHead>
                <TableHead>Id</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {spring.entities.map((e) => (
                <TableRow key={e.typeName}>
                  <TableCell><TypeNameLink name={e.entityName} qualifiedName={e.typeName} /></TableCell>
                  <TableCell className="font-mono text-xs">{e.tableName ?? <span className="text-muted-foreground">default</span>}</TableCell>
                  <TableCell className="font-mono text-xs">{e.idField ?? "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </section>
      )}
      {empty && <p className="text-sm text-muted-foreground">No configuration files, properties or entities found.</p>}
    </div>
  )
}

function ConfigurationKeys({ keys }: { keys: ConfigurationKey[] }) {
  const [query, setQuery] = useState("")
  const statuses = (Object.keys(STATUS) as ConfigurationKeyStatus[]).filter((s) => keys.some((k) => k.status === s))
  const [shown, setShown] = useState<string[]>(statuses)
  const q = query.trim().toLowerCase()
  const visible = keys.filter((k) => shown.includes(k.status)
    && (!q || `${k.key} ${k.description ?? ""} ${k.values.map((v) => v.value).join(" ")}`.toLowerCase().includes(q)))
  return (
    <section className="flex flex-col gap-3">
      <h2 className="font-heading text-xl font-semibold">Keys</h2>
      <p className="max-w-3xl text-sm text-muted-foreground">
        Every key the configuration files set, with what reads it: the project's @ConfigurationProperties, @Value,
        @ConditionalOnProperty and Environment calls, or a library's configuration metadata.
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <InputGroup className="max-w-sm">
          <InputGroupInput placeholder="Filter keys…" value={query} onChange={(e) => setQuery(e.target.value)} />
          <InputGroupAddon>
            <SearchIcon />
          </InputGroupAddon>
        </InputGroup>
        <ToggleGroup multiple value={shown} onValueChange={(value) => setShown(value as string[])} variant="outline" size="sm">
          {statuses.map((s) => (
            <ToggleGroupItem key={s} value={s} title={STATUS[s].title}>
              {STATUS[s].label} ({keys.filter((k) => k.status === s).length})
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Key</TableHead>
            <TableHead>Value</TableHead>
            <TableHead>Read by</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {visible.map((k) => (
            <TableRow key={k.key}>
              <TableCell className="max-w-md align-top whitespace-normal">
                <div className="flex flex-wrap items-center gap-1.5">
                  <code className="font-mono text-xs break-all">{k.key}</code>
                  <Badge variant={STATUS[k.status].variant} title={STATUS[k.status].title}>{STATUS[k.status].label}</Badge>
                  {k.deprecated && <Badge variant="outline">deprecated</Badge>}
                </div>
                {k.description && <p className="mt-1 text-xs text-muted-foreground">{k.description}</p>}
                {k.suggestion && <p className="mt-1 text-xs">Did you mean <code className="font-mono">{k.suggestion}</code>?</p>}
              </TableCell>
              <TableCell className="align-top font-mono text-xs whitespace-normal">
                {k.values.map((v, i) => (
                  <div key={i} className="flex flex-wrap items-baseline gap-x-1.5">
                    {v.profile && <Badge variant="outline">{v.profile}</Badge>}
                    <span className="break-all">{v.value || <span className="text-muted-foreground">(empty)</span>}</span>
                    <FileLink path={v.path} line={v.line} moduleId={v.moduleId} />
                  </div>
                ))}
                {k.defaultValue && <div className="text-muted-foreground">default {k.defaultValue}</div>}
              </TableCell>
              <TableCell className="align-top font-mono text-xs whitespace-normal">
                {k.boundTo.length === 0 && <span className="text-muted-foreground">—</span>}
                {k.boundTo.map((b) => {
                  const [type, member] = b.split("#")
                  return (
                    <div key={b}>
                      <TypeNameLink name={simple(type)} qualifiedName={type} />
                      {member && <span className="text-muted-foreground">.{member}</span>}
                    </div>
                  )
                })}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </section>
  )
}

function ConfigurationFileView({ file }: { file: ConfigurationFile }) {
  return (
    <Collapsible id={fileAnchor(file.path)} className="rounded-xl border">
      <CollapsibleTrigger className="group/trigger flex w-full items-center gap-2 px-4 py-3 text-left text-sm">
        <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[panel-open]/trigger:rotate-90" />
        <span className="font-mono">{file.path}</span>
        {file.profile && <Badge variant="outline">profile: {file.profile}</Badge>}
        <span className="ml-auto text-xs text-muted-foreground">{file.entries.length} keys</span>
      </CollapsibleTrigger>
      <CollapsibleContent className="px-4 pb-4">
        <JavaCode code={file.content} language={file.format} />
      </CollapsibleContent>
    </Collapsible>
  )
}

function PropertyTable({ rows, typeLabel = "Type" }: { rows: { key: string; type: string; value?: string; note?: string }[]; typeLabel?: string }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Key</TableHead>
          <TableHead>{typeLabel}</TableHead>
          <TableHead>Default</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((r) => (
          <TableRow key={r.key + r.type}>
            <TableCell className="align-top font-mono text-xs">
              {r.key}
              {r.note && <div className="mt-1 font-sans whitespace-normal text-muted-foreground">{r.note}</div>}
            </TableCell>
            <TableCell className="align-top font-mono text-xs">{r.type}</TableCell>
            <TableCell className="align-top font-mono text-xs">{r.value ?? <span className="text-muted-foreground">—</span>}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
