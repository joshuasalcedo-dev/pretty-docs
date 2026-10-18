import { useDocs } from "@/lib/docs/context"
import { hasModifier } from "@/lib/docs/model"
import type { TypeStructure } from "@/lib/docs/types"
import { cn } from "@/lib/utils"

export type Kind = "class" | "abstract" | "interface" | "enum" | "record" | "annotation" | "alias" | "function" | "variable" | "namespace"

const KIND: Record<Kind, { letter: string; color: string; label: string }> = {
  class: { letter: "C", color: "text-kind-class", label: "Class" },
  abstract: { letter: "A", color: "text-kind-abstract", label: "Abstract class" },
  interface: { letter: "I", color: "text-kind-interface", label: "Interface" },
  enum: { letter: "E", color: "text-kind-enum", label: "Enum" },
  record: { letter: "R", color: "text-kind-record", label: "Record" },
  annotation: { letter: "@", color: "text-kind-annotation", label: "Annotation" },
  // TypeScript declarations, on the same palette
  alias: { letter: "T", color: "text-kind-record", label: "Type alias" },
  function: { letter: "ƒ", color: "text-kind-class", label: "Function" },
  variable: { letter: "V", color: "text-kind-annotation", label: "Variable" },
  namespace: { letter: "N", color: "text-kind-enum", label: "Namespace" },
}

type Badge = "spring" | "entity" | "exception" | "test"

const BADGE_LABEL: Record<Badge, string> = {
  spring: "Spring bean",
  entity: "JPA entity",
  exception: "Exception",
  test: "Test",
}

function kindOf(type: TypeStructure): Kind {
  switch (type.kind) {
    case "INTERFACE":
      return "interface"
    case "ENUM":
      return "enum"
    case "RECORD":
      return "record"
    case "ANNOTATION":
      return "annotation"
    case "TYPE_ALIAS":
    case "FUNCTION":
    case "VARIABLE":
    case "NAMESPACE":
      return iconKind(type.kind)
    default:
      return hasModifier(type.modifiers, "ABSTRACT") ? "abstract" : "class"
  }
}

/** Framework-aware marker: Spring bean, JPA entity, exception or test class. */
function useBadge(type: TypeStructure): Badge | undefined {
  const { site } = useDocs()
  if (site.spring?.beans.some((b) => b.typeName === type.qualifiedName)) return "spring"
  if (site.spring?.entities.some((e) => e.typeName === type.qualifiedName)) return "entity"
  const parent = type.superclass?.name ?? ""
  if (/(Exception|Error|Throwable)$/.test(parent)) return "exception"
  if (/(Test|Tests|IT)$/.test(type.name) || type.annotations.some((a) => a.name === "SpringBootTest")) return "test"
  return undefined
}

/**
 * Language-aware type icon (letter disc per kind, like IDE structure views) with a small
 * framework badge. Colors are theme tokens, so it follows light/dark mode.
 */
export function TypeIcon({ type, className }: { type: TypeStructure; className?: string }) {
  return <KindIcon kind={kindOf(type)} badge={useBadge(type)} className={className} />
}

/** The icon for a kind on its own, e.g. for legends. */
export function KindIcon({ kind: key, badge, className }: { kind: Kind; badge?: Badge; className?: string }) {
  const kind = KIND[key]
  const title = badge ? `${kind.label} · ${BADGE_LABEL[badge]}` : kind.label
  return (
    <svg viewBox="0 0 16 16" role="img" aria-label={title} className={cn("size-4 shrink-0", kind.color, className)}>
      <title>{title}</title>
      <circle cx="8" cy="8" r="7" fill="currentColor" fillOpacity="0.14" stroke="currentColor" strokeWidth="1.2"
        strokeDasharray={kind.letter === "A" ? "2.2 1.4" : undefined} />
      <text x="8" y="8" dy="0.36em" textAnchor="middle" fontSize="8.5" fontWeight="700" fill="currentColor"
        fontFamily="ui-sans-serif, system-ui, sans-serif">
        {kind.letter}
      </text>
      {badge && <BadgeMark badge={badge} />}
    </svg>
  )
}

/** Icon kind for a TypeKind (classes are drawn as plain classes). */
export function iconKind(kind: TypeStructure["kind"]): Kind {
  if (kind === "TYPE_ALIAS") return "alias"
  return kind === "CLASS" ? "class" : (kind.toLowerCase() as Kind)
}

function BadgeMark({ badge }: { badge: Badge }) {
  // Bottom-right corner, on a background-colored ring so it reads on top of the disc.
  const fill = {
    spring: "var(--color-kind-interface)",
    entity: "var(--color-kind-enum)",
    exception: "var(--color-destructive)",
    test: "var(--color-kind-record)",
  }[badge]
  return (
    <g>
      <circle cx="12.6" cy="12.6" r="3.4" fill="var(--color-background)" />
      <circle cx="12.6" cy="12.6" r="2.5" fill={fill} />
    </g>
  )
}

