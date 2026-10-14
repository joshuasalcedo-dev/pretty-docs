import { Fragment, type ReactNode } from "react"

import { useDocs } from "@/lib/docs/context"
import { resolveType } from "@/lib/docs/javadoc"
import { externalTypeUrl, routes, type DocsIndex } from "@/lib/docs/model"
import type { TypeReference } from "@/lib/docs/types"

const INTERNAL = "text-primary underline-offset-4 hover:underline"
const EXTERNAL = "underline decoration-dotted decoration-muted-foreground/60 underline-offset-4 hover:decoration-solid"

/**
 * Where a type links to: its page in this site, its page in an external javadoc site
 * (element-list), or nowhere. Unqualified names (source mode) fall back to a unique documented
 * type with that simple name.
 */
function target(index: DocsIndex, name: string, qualifiedName?: string) {
  const internal = qualifiedName && index.types.has(qualifiedName) ? qualifiedName : !qualifiedName ? resolveType(name, index) : undefined
  if (internal) return { href: routes.type(internal), external: false, title: internal }
  const external = qualifiedName ? externalTypeUrl(index, qualifiedName) : undefined
  if (external) return { href: external.url, external: true, title: `${qualifiedName} (${external.label})` }
  return undefined
}

function NameLink({ name, qualifiedName, children }: { name: string; qualifiedName?: string; children?: ReactNode }) {
  const index = useDocs()
  const link = target(index, name, qualifiedName)
  if (!link) return <span title={qualifiedName}>{children ?? name}</span>
  return (
    <a
      href={link.href}
      title={link.title}
      className={link.external ? EXTERNAL : INTERNAL}
      {...(link.external ? { target: "_blank", rel: "noreferrer" } : {})}
    >
      {children ?? name}
    </a>
  )
}

/** A type with generics; documented types link to their page. */
export function TypeRef({ type, varargs }: { type: TypeReference; varargs?: boolean }) {
  if (type.isWildcard) {
    return (
      <span>
        ?
        {type.wildcardBound && type.wildcardKind !== "UNBOUNDED" && (
          <>
            {type.wildcardKind === "EXTENDS" ? " extends " : " super "}
            <TypeRef type={type.wildcardBound} />
          </>
        )}
      </span>
    )
  }
  const dimensions = type.isArray ? type.arrayDimensions : 0
  return (
    <span>
      {type.isPrimitive ? type.name : <NameLink name={type.name} qualifiedName={type.qualifiedName} />}
      {type.typeArguments && type.typeArguments.length > 0 && (
        <>
          {"<"}
          {type.typeArguments.map((argument, i) => (
            <Fragment key={i}>
              {i > 0 && ", "}
              <TypeRef type={argument} />
            </Fragment>
          ))}
          {">"}
        </>
      )}
      {varargs ? "[]".repeat(Math.max(dimensions - 1, 0)) + "..." : "[]".repeat(dimensions)}
    </span>
  )
}

/** Link to a type by name: this site, external javadoc, or plain text. */
export function TypeNameLink({ name, qualifiedName, children }: { name: string; qualifiedName?: string; children?: ReactNode }) {
  return <NameLink name={name} qualifiedName={qualifiedName}>{children}</NameLink>
}
