import { ChevronRightIcon, CodeIcon } from "lucide-react"
import { Fragment, useEffect, type ReactNode } from "react"

import { JavaCode } from "@/components/docs/java-code"
import { JavadocText } from "@/components/docs/javadoc-text"
import { TypeNameLink, TypeRef } from "@/components/docs/type-ref"
import { Badge } from "@/components/ui/badge"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { Separator } from "@/components/ui/separator"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useDocs } from "@/lib/docs/context"
import {
  displayName,
  externalTypeUrl,
  hasModifier,
  isDeprecated,
  kindLabel,
  memberId,
  modifiersToString,
  routes,
  summary,
  tags,
  type IndexedType,
} from "@/lib/docs/model"
import { scrollToElement } from "@/lib/scroll"
import { FindingsTable, useTypeFindings } from "@/pages/quality-page"
import { BeanCard } from "@/pages/spring-page"
import { TypeIcon } from "@/components/docs/type-icon"
import type { Annotation, Constructor, Field, Javadoc, MemberReference, Method, TypeParameter, TypeStructure } from "@/lib/docs/types"

const KIND_KEYWORD: Record<TypeStructure["kind"], string> = {
  CLASS: "class",
  INTERFACE: "interface",
  ENUM: "enum",
  RECORD: "record",
  ANNOTATION: "@interface",
  TYPE_ALIAS: "type",
  FUNCTION: "function",
  VARIABLE: "const",
  NAMESPACE: "namespace",
}

/** A declaration as its language writes it (TypeScript), highlighted. */
function WrittenSignature({ code, language }: { code: string; language: string }) {
  return <JavaCode code={code} language={language === "typescript" ? "typescript" : "java"} />
}

export function TypePage({ indexed, member }: { indexed: IndexedType; member?: string }) {
  const { type, packageName } = indexed
  const language = indexed.file.language ?? "java"
  // TypeScript: a function's overloads, a variable's declaration (already the header), a namespace's members
  const methodsTitle = type.kind === "FUNCTION" ? (type.methods.length > 1 ? "Overloads" : "Signature") : "Methods"
  const showFields = type.kind !== "VARIABLE"
  const nestedTitle = type.kind === "NAMESPACE" ? "Declarations" : "Nested types"
  const findings = useTypeFindings(type.qualifiedName)

  useEffect(() => {
    if (!scrollToElement(member ? document.getElementById(member) : null)) window.scrollTo({ top: 0 })
  }, [type.qualifiedName, member])

  const sections = [
    { id: "findings", label: `Findings (${findings.length})`, show: findings.length > 0 },
    { id: "constants", label: "Enum constants", show: type.enumConstants.length > 0 },
    { id: "components", label: "Record components", show: type.recordComponents.length > 0 },
    { id: "fields", label: language === "typescript" ? "Properties" : "Fields", show: showFields && type.fields.length > 0 },
    { id: "constructors", label: "Constructors", show: type.constructors.length > 0 },
    { id: "methods", label: methodsTitle, show: type.methods.length > 0 },
    { id: "nested", label: nestedTitle, show: type.nestedTypes.length > 0 },
    {
      id: "inherited",
      label: "Inherited members",
      show: (type.inheritedMethods?.length ?? 0) > 0 || (type.inheritedFields?.length ?? 0) > 0,
    },
  ].filter((s) => s.show)

  return (
    <div className="flex gap-10">
      <article className="flex min-w-0 flex-1 flex-col gap-8">
        <header className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <TypeIcon type={type} className="size-5" />
            <Badge variant="secondary">{kindLabel(type)}</Badge>
            <a href={routes.pkg(indexed.module.id, packageName)} className="font-mono underline-offset-4 hover:text-foreground hover:underline">
              {packageName || (language === "typescript" ? "(project root)" : "(default package)")}
            </a>
            {isDeprecated(type) && <Badge variant="destructive">Deprecated</Badge>}
          </div>
          <h1 className="font-heading text-4xl font-bold tracking-tight break-words">
            {displayName(indexed)}
            <TypeParams params={type.typeParameters} className="text-muted-foreground" />
          </h1>
          {type.signature ? <WrittenSignature code={type.signature} language={language} /> : <Signature>
            <Annotations annotations={type.annotations} />
            <span className="text-muted-foreground">{modifiersToString(type.modifiers)}</span> {KIND_KEYWORD[type.kind]}{" "}
            <span className="font-semibold">{type.name}</span>
            <TypeParams params={type.typeParameters} />
            {type.superclass && (
              <>
                {" "}<span className="text-muted-foreground">extends</span>{" "}
                <TypeNameLink name={type.superclass.name} qualifiedName={type.superclass.qualifiedName} />
              </>
            )}
            {type.interfaces.length > 0 && (
              <>
                {" "}<span className="text-muted-foreground">{type.kind === "INTERFACE" ? "extends" : "implements"}</span>{" "}
                {type.interfaces.map((i, n) => (
                  <Fragment key={i.name + n}>
                    {n > 0 && ", "}
                    <TypeNameLink name={i.name} qualifiedName={i.qualifiedName} />
                  </Fragment>
                ))}
              </>
            )}
          </Signature>}
          {indexed.outer && (
            <p className="text-sm text-muted-foreground">
              Nested in <TypeNameLink name={displayName(indexed.outer)} qualifiedName={indexed.outer.type.qualifiedName} />
            </p>
          )}
        </header>

        <Description doc={type.javadoc} packageName={packageName} />
        <DocTags doc={type.javadoc} />

        <SpringBeans type={type} />

        {type.usedBy.length > 0 && (
          <section className="flex flex-col gap-2">
            <h2 className="text-sm font-medium">Used by</h2>
            <div className="flex flex-wrap gap-1.5">
              {type.usedBy.map((user) => (
                <Badge key={user} variant="outline" render={<a href={routes.type(user)} />}>
                  {user.split(".").pop()}
                </Badge>
              ))}
            </div>
          </section>
        )}

        {findings.length > 0 && (
          <Section id="findings" title="Findings">
            <FindingsTable findings={findings} showType={findings.some((f) => f.typeName !== type.qualifiedName)} />
          </Section>
        )}

        {type.enumConstants.length > 0 && (
          <Section id="constants" title="Enum constants">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Constant</TableHead>
                  <TableHead>Description</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {type.enumConstants.map((c) => (
                  <TableRow key={c.name} id={c.name}>
                    <TableCell className="align-top font-mono text-sm">
                      {c.name}
                      {c.arguments.length > 0 && <span className="text-muted-foreground">({c.arguments.join(", ")})</span>}
                    </TableCell>
                    <TableCell className="whitespace-normal">
                      {c.javadoc ? <JavadocText text={c.javadoc.description} packageName={packageName} /> : <Muted />}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Section>
        )}

        {type.recordComponents.length > 0 && (
          <Section id="components" title="Record components">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Component</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Description</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {type.recordComponents.map((c) => (
                  <TableRow key={c.name}>
                    <TableCell className="font-mono text-sm">{c.name}</TableCell>
                    <TableCell className="font-mono text-sm"><TypeRef type={c.type} /></TableCell>
                    <TableCell className="whitespace-normal">
                      {c.javadoc ? <JavadocText text={c.javadoc.description} packageName={packageName} /> : <Muted />}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Section>
        )}

        {showFields && type.fields.length > 0 && (
          <Section id="fields" title={language === "typescript" ? "Properties" : "Fields"}>
            {type.fields.map((f) => <FieldCard key={f.name} field={f} packageName={packageName} language={language} />)}
          </Section>
        )}

        {type.constructors.length > 0 && (
          <Section id="constructors" title="Constructors">
            {type.constructors.map((c) => <MemberCard key={memberId(c)} member={c} packageName={packageName} typeName={type.qualifiedName} language={language} />)}
          </Section>
        )}

        {type.methods.length > 0 && (
          <Section id="methods" title={methodsTitle}>
            {(type.kind === "FUNCTION" ? type.methods : [...type.methods].sort((a, b) => a.name.localeCompare(b.name)))
              .map((m, i) => (
                <MemberCard key={memberId(m) + i} member={m} packageName={packageName} typeName={type.qualifiedName} language={language}
                  repeatsHeader={!!m.signature && m.signature === type.signature} />
              ))}
          </Section>
        )}

        {type.nestedTypes.length > 0 && (
          <Section id="nested" title={nestedTitle}>
            <ul className="flex flex-col gap-2">
              {type.nestedTypes.map((n) => (
                <li key={n.qualifiedName} className="flex flex-col">
                  <a href={routes.type(n.qualifiedName)} className="font-mono text-sm font-medium text-primary underline-offset-4 hover:underline">
                    {n.name}
                  </a>
                  <span className="text-sm text-muted-foreground">{summary(n.javadoc)}</span>
                </li>
              ))}
            </ul>
          </Section>
        )}

        {((type.inheritedMethods?.length ?? 0) > 0 || (type.inheritedFields?.length ?? 0) > 0) && (
          <Section id="inherited" title="Inherited members">
            <InheritedMembers type={type} />
          </Section>
        )}

        <p className="text-xs text-muted-foreground">
          {indexed.file.filePath ?? indexed.file.fileName}, lines {type.startLine}–{type.endLine}
        </p>
      </article>

      <OnThisPage type={type} sections={sections} />
    </div>
  )
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className="flex scroll-mt-20 flex-col gap-4">
      <h2 className="font-heading text-3xl font-bold tracking-tight">{title}</h2>
      <Separator />
      {children}
    </section>
  )
}

function Signature({ children }: { children: ReactNode }) {
  return (
    <pre className="overflow-x-auto rounded-lg border bg-muted/50 px-4 py-3 font-mono text-sm leading-relaxed whitespace-pre-wrap">
      {children}
    </pre>
  )
}

function Annotations({ annotations }: { annotations: Annotation[] }) {
  if (annotations.length === 0) return null
  return (
    <>
      {annotations.map((a) => (
        <Fragment key={a.name}>
          <span className="text-chart-4">
            @{a.name}
            {a.values && Object.keys(a.values).length > 0 &&
              `(${Object.entries(a.values).map(([k, v]) => (k === "value" ? v : `${k} = ${v}`)).join(", ")})`}
          </span>
          {"\n"}
        </Fragment>
      ))}
    </>
  )
}

function TypeParams({ params, className }: { params: TypeParameter[]; className?: string }) {
  if (params.length === 0) return null
  return (
    <span className={className}>
      {"<"}
      {params.map((p, i) => (
        <Fragment key={p.name}>
          {i > 0 && ", "}
          {p.name}
          {p.bounds.length > 0 && (
            <>
              {" extends "}
              {p.bounds.map((b, j) => (
                <Fragment key={j}>
                  {j > 0 && " & "}
                  <TypeRef type={b} />
                </Fragment>
              ))}
            </>
          )}
        </Fragment>
      ))}
      {">"}
    </span>
  )
}

function Description({ doc, packageName }: { doc?: Javadoc; packageName: string }) {
  if (!doc?.description) return <Muted text="No description." />
  return <JavadocText text={doc.description} packageName={packageName} className="max-w-3xl" />
}

function Muted({ text = "—" }: { text?: string }) {
  return <p className="text-sm text-muted-foreground italic">{text}</p>
}

const INFO_TAGS = ["@author", "@version", "@since", "@see", "@deprecated", "@apiNote", "@implNote", "@implSpec"]

/** Tags that describe the element as a whole (not params/returns/throws). */
function DocTags({ doc }: { doc?: Javadoc }) {
  const shown = INFO_TAGS.map((name) => ({ name, values: tags(doc, name) })).filter((t) => t.values.length > 0)
  if (shown.length === 0) return null
  return (
    <dl className="grid max-w-3xl grid-cols-[8rem_1fr] gap-x-4 gap-y-2 text-sm">
      {shown.map((t) => (
        <Fragment key={t.name}>
          <dt className="text-muted-foreground capitalize">{t.name.slice(1).replace(/([A-Z])/g, " $1")}</dt>
          <dd className="flex flex-col gap-1">
            {t.values.map((v) => <JavadocText key={v.position} text={v.text} />)}
          </dd>
        </Fragment>
      ))}
    </dl>
  )
}

function FieldCard({ field, packageName, language }: { field: Field; packageName: string; language: string }) {
  return (
    <div id={field.name} className="flex scroll-mt-20 flex-col gap-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="font-mono text-base font-semibold">{field.name}</h3>
        {hasModifier(field.modifiers, "STATIC") && <Badge variant="outline">static</Badge>}
        {hasModifier(field.modifiers, "FINAL") && <Badge variant="outline">final</Badge>}
        {isDeprecated(field) && <Badge variant="destructive">Deprecated</Badge>}
      </div>
      {field.signature ? <WrittenSignature code={field.signature} language={language} /> : <Signature>
        <Annotations annotations={field.annotations} />
        <span className="text-muted-foreground">{modifiersToString(field.modifiers)}</span> <TypeRef type={field.type} /> {field.name}
        {field.initializer && field.initializer.length <= 80 && <span className="text-muted-foreground"> = {field.initializer}</span>}
      </Signature>}
      {field.javadoc && <JavadocText text={field.javadoc.description} packageName={packageName} />}
    </div>
  )
}

function MemberCard({ member, packageName, typeName, language, repeatsHeader = false }: {
  member: Method | Constructor
  packageName: string
  typeName: string
  language: string
  /** The signature is the page header's already (a function with one signature): not shown twice. */
  repeatsHeader?: boolean
}) {
  const id = memberId(member)
  const method = "returnType" in member ? (member as Method) : undefined
  const doc = member.javadoc
  const params = tags(doc, "@param")
  const returns = tags(doc, "@return")[0]
  const throws = [...tags(doc, "@throws"), ...tags(doc, "@exception")]

  return (
    <div id={id} className="flex scroll-mt-20 flex-col gap-3 rounded-xl border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="font-mono text-base font-semibold">
          <a href={routes.type(typeName, id)} className="hover:underline">
            {member.name}
          </a>
        </h3>
        {hasModifier(member.modifiers, "STATIC") && <Badge variant="outline">static</Badge>}
        {hasModifier(member.modifiers, "ABSTRACT") && <Badge variant="outline">abstract</Badge>}
        {hasModifier(member.modifiers, "DEFAULT") && <Badge variant="outline">default</Badge>}
        {isDeprecated(member) && <Badge variant="destructive">Deprecated</Badge>}
        {method && method.cyclomaticComplexity > 10 && (
          <Badge variant="secondary" title="Cyclomatic complexity">complexity {method.cyclomaticComplexity}</Badge>
        )}
      </div>

      {repeatsHeader ? null : member.signature ? <WrittenSignature code={member.signature} language={language} /> : <Signature>
        <Annotations annotations={member.annotations} />
        <span className="text-muted-foreground">{modifiersToString(member.modifiers)}</span>
        {member.typeParameters.length > 0 && <> <TypeParams params={member.typeParameters} /></>}
        {method?.returnType && <> <TypeRef type={method.returnType} /></>} <span className="font-semibold">{member.name}</span>(
        {member.parameters.map((p, i) => (
          <Fragment key={p.name}>
            {i > 0 && ", "}
            {member.parameters.length > 2 && "\n    "}
            <TypeRef type={p.type} varargs={p.isVarArgs} /> {p.name}
          </Fragment>
        ))}
        {member.parameters.length > 2 && "\n"})
        {member.thrownExceptions.length > 0 && (
          <>
            {" "}<span className="text-muted-foreground">throws</span>{" "}
            {member.thrownExceptions.map((t, i) => (
              <Fragment key={i}>
                {i > 0 && ", "}
                <TypeRef type={t} />
              </Fragment>
            ))}
          </>
        )}
      </Signature>}

      {doc?.description && <JavadocText text={doc.description} packageName={packageName} />}
      {doc?.inheritedFrom && (
        <p className="text-xs text-muted-foreground">
          Description copied from{" "}
          <TypeNameLink name={doc.inheritedFrom.split(".").pop()!} qualifiedName={doc.inheritedFrom} />
        </p>
      )}

      {(member.parameters.length > 0 || returns || throws.length > 0) && (
        <dl className="grid grid-cols-[6rem_1fr] gap-x-4 gap-y-2 text-sm">
          {member.parameters.length > 0 && (
            <>
              <dt className="text-muted-foreground">Parameters</dt>
              <dd className="flex flex-col gap-1">
                {member.parameters.map((p) => {
                  const tag = params.find((t) => t.paramName === p.name)
                  return (
                    <div key={p.name} className="flex flex-wrap gap-x-2">
                      <code className="font-mono text-sm">{p.name}</code>
                      {tag ? <JavadocText text={tag.text} packageName={packageName} className="inline" /> : <span className="text-muted-foreground">—</span>}
                    </div>
                  )
                })}
              </dd>
            </>
          )}
          {returns && (
            <>
              <dt className="text-muted-foreground">Returns</dt>
              <dd><JavadocText text={returns.text} packageName={packageName} /></dd>
            </>
          )}
          {throws.length > 0 && (
            <>
              <dt className="text-muted-foreground">Throws</dt>
              <dd className="flex flex-col gap-1">
                {throws.map((t) => <JavadocText key={t.position} text={t.text} packageName={packageName} />)}
              </dd>
            </>
          )}
        </dl>
      )}

      <DocTags doc={doc} />

      {member.calledBy.length > 0 && (
        <p className="text-xs text-muted-foreground">
          Called by {[...new Set(member.calledBy.map((c) => c.methodName))].join(", ")}
        </p>
      )}

      {member.body && (
        <Collapsible>
          <CollapsibleTrigger className="group/source inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground">
            <ChevronRightIcon className="size-3.5 transition-transform group-data-[panel-open]/source:rotate-90" />
            <CodeIcon className="size-3.5" />
            Source{method ? ` · ${method.lineCount} lines` : ""}
          </CollapsibleTrigger>
          <CollapsibleContent>
            <JavaCode code={member.body} className="mt-2" language={language === "typescript" ? "tsx" : "java"} />
          </CollapsibleContent>
        </Collapsible>
      )}
    </div>
  )
}

function OnThisPage({ type, sections }: { type: TypeStructure; sections: { id: string; label: string }[] }) {
  const index = useDocs()
  if (sections.length === 0) return null
  const base = routes.type(type.qualifiedName)
  return (
    <nav className="sticky top-20 hidden max-h-[calc(100svh-6rem)] w-56 shrink-0 flex-col gap-2 overflow-y-auto text-sm xl:flex">
      <p className="font-medium">On this page</p>
      {sections.map((s) => (
        <div key={s.id} className="flex flex-col gap-1">
          <a href={`${base}/${s.id}`} className="text-muted-foreground hover:text-foreground">{s.label}</a>
          {s.id === "methods" && (
            <div className="flex flex-col gap-1 border-l pl-3">
              {[...type.methods]
                .sort((a, b) => a.name.localeCompare(b.name))
                .filter((m, i, all) => all.findIndex((o) => o.name === m.name) === i)
                .map((m) => (
                  <a key={m.name} href={routes.type(type.qualifiedName, memberId(m))} className="truncate font-mono text-xs text-muted-foreground hover:text-foreground">
                    {m.name}
                  </a>
                ))}
            </div>
          )}
        </div>
      ))}
      {index.site.visibility !== "private" && (
        <p className="mt-2 text-xs text-muted-foreground">Showing {index.site.visibility} and wider members.</p>
      )}
    </nav>
  )
}

/** Members inherited from supertypes (resolved mode), grouped like javadoc's "Methods inherited from class X". */
function InheritedMembers({ type }: { type: TypeStructure }) {
  const index = useDocs()
  const groups = new Map<string, { name: string; methods: MemberReference[]; fields: string[] }>()
  const group = (qualified: string, name: string) => {
    if (!groups.has(qualified)) groups.set(qualified, { name, methods: [], fields: [] })
    return groups.get(qualified)!
  }
  for (const m of type.inheritedMethods ?? []) {
    group(m.qualifiedDeclaringTypeName ?? m.declaringTypeName ?? "?", m.declaringTypeName ?? "?").methods.push(m)
  }
  for (const f of type.inheritedFields ?? []) {
    group(f.qualifiedDeclaringTypeName ?? f.declaringTypeName ?? "?", f.declaringTypeName ?? "?").fields.push(f.fieldName)
  }

  const memberLink = (declaring: string, label: string, anchor: string, javadocAnchor: string) => {
    if (index.types.has(declaring)) {
      return <a href={routes.type(declaring, anchor)} className="text-primary underline-offset-4 hover:underline">{label}</a>
    }
    const external = externalTypeUrl(index, declaring, javadocAnchor)
    if (external) {
      return (
        <a href={external.url} target="_blank" rel="noreferrer" className="underline decoration-dotted decoration-muted-foreground/60 underline-offset-4 hover:decoration-solid">
          {label}
        </a>
      )
    }
    return <span>{label}</span>
  }

  return (
    <div className="flex flex-col gap-4">
      {[...groups.entries()].map(([qualified, g]) => (
        <div key={qualified} className="flex flex-col gap-1.5 rounded-xl border p-4">
          <p className="text-sm">
            <span className="text-muted-foreground">From </span>
            <TypeNameLink name={g.name} qualifiedName={qualified}>
              <code className="font-mono">{qualified}</code>
            </TypeNameLink>
          </p>
          {g.methods.length > 0 && (
            <p className="flex flex-wrap gap-x-3 gap-y-1 font-mono text-xs">
              {g.methods
                .filter((m, i, all) => all.findIndex((o) => o.methodName === m.methodName) === i)
                .map((m) => {
                  const overloads = g.methods.filter((o) => o.methodName === m.methodName).length
                  const params = m.parameterTypes ?? []
                  const anchor = `${m.methodName}(${params.map((p) => p.name + "[]".repeat(p.isArray ? p.arrayDimensions : 0)).join(",")})`
                  const javadocAnchor = `${m.methodName}(${params.map((p) => (p.qualifiedName ?? p.name) + "[]".repeat(p.isArray ? p.arrayDimensions : 0)).join(",")})`
                  return (
                    <span key={m.methodName}>
                      {memberLink(qualified, m.methodName, anchor, javadocAnchor)}
                      {overloads > 1 && <span className="text-muted-foreground"> ×{overloads}</span>}
                    </span>
                  )
                })}
            </p>
          )}
          {g.fields.length > 0 && (
            <p className="flex flex-wrap gap-x-3 gap-y-1 font-mono text-xs">
              <span className="text-muted-foreground">fields:</span>
              {g.fields.map((f) => (
                <span key={f}>{memberLink(qualified, f, f, f)}</span>
              ))}
            </p>
          )}
        </div>
      ))}
    </div>
  )
}

/** Beans this type defines (as a component or through @Bean methods), from the Spring analysis. */
function SpringBeans({ type }: { type: TypeStructure }) {
  const { site } = useDocs()
  const beans = site.spring?.beans.filter((b) => b.typeName === type.qualifiedName || b.declaringType === type.qualifiedName) ?? []
  if (beans.length === 0) return null
  const endpoints = site.spring?.endpoints.filter((e) => e.controllerType === type.qualifiedName).length ?? 0
  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-sm font-medium">
        Spring {beans.length === 1 ? "bean" : "beans"}
        {endpoints > 0 && (
          <a href={routes.spring("endpoints")} className="ml-2 text-xs font-normal text-primary underline-offset-4 hover:underline">
            {endpoints} endpoint{endpoints === 1 ? "" : "s"}
          </a>
        )}
      </h2>
      <div className="grid gap-3 lg:grid-cols-2">
        {beans.map((bean) => <BeanCard key={bean.name} bean={bean} />)}
      </div>
    </section>
  )
}
