import { BoxIcon, BracesIcon, GlobeIcon, LeafIcon, PackageIcon, SquareFunctionIcon, VariableIcon } from "lucide-react"
import { useMemo, useState } from "react"

import {
  Command,
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command"
import { useDocs } from "@/lib/docs/context"
import type { SearchEntry } from "@/lib/docs/model"

const GROUPS: { kind: SearchEntry["kind"]; label: string; icon: typeof BoxIcon }[] = [
  { kind: "type", label: "Types", icon: BracesIcon },
  { kind: "method", label: "Methods", icon: SquareFunctionIcon },
  { kind: "field", label: "Fields", icon: VariableIcon },
  { kind: "endpoint", label: "Endpoints", icon: GlobeIcon },
  { kind: "bean", label: "Beans", icon: LeafIcon },
  { kind: "package", label: "Packages", icon: PackageIcon },
  { kind: "module", label: "Modules", icon: BoxIcon },
]

const LIMIT_PER_GROUP = 8

/** Exact and prefix matches on the name rank before substring matches. */
function score(entry: SearchEntry, query: string) {
  const name = entry.label.toLowerCase()
  if (name === query) return 0
  if (name.startsWith(query) || name.includes(`.${query}`)) return 1
  if (entry.keywords.includes(query)) return 2
  return -1
}

export function SearchDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const index = useDocs()
  const [query, setQuery] = useState("")

  const results = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return GROUPS.map((g) => ({ ...g, entries: index.search.filter((e) => e.kind === g.kind).slice(0, 5) }))
    return GROUPS.map((group) => ({
      ...group,
      entries: index.search
        .filter((e) => e.kind === group.kind)
        .map((e) => ({ e, s: score(e, q) }))
        .filter((r) => r.s >= 0)
        .sort((a, b) => a.s - b.s || a.e.label.length - b.e.label.length)
        .slice(0, LIMIT_PER_GROUP)
        .map((r) => r.e),
    }))
  }, [index, query])

  const go = (href: string) => {
    window.location.hash = href
    onOpenChange(false)
    setQuery("")
  }

  return (
    <CommandDialog open={open} onOpenChange={onOpenChange} title="Search documentation" description="Find types, methods, fields, packages and modules">
      <Command shouldFilter={false}>
        <CommandInput placeholder="Search types, methods, fields…" value={query} onValueChange={setQuery} />
        <CommandList>
          <CommandEmpty>No results.</CommandEmpty>
          {results
            .filter((g) => g.entries.length > 0)
            .map((group) => (
              <CommandGroup key={group.kind} heading={group.label}>
                {group.entries.map((entry) => (
                  <CommandItem key={entry.href} value={entry.href} onSelect={() => go(entry.href)}>
                    <group.icon />
                    <span className="truncate">{entry.label}</span>
                    <span className="ml-auto truncate text-xs text-muted-foreground">{entry.detail}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            ))}
        </CommandList>
      </Command>
    </CommandDialog>
  )
}
