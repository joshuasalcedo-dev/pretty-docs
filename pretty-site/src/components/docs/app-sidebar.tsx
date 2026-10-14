import { BarChart3Icon, BoxIcon, ChevronRightIcon, FolderTreeIcon, LeafIcon, ListIcon, PackageIcon, SearchIcon, ListTreeIcon } from "lucide-react"
import { useMemo, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from "react"

import { TypeIcon } from "@/components/docs/type-icon"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { Kbd } from "@/components/ui/kbd"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInput,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
} from "@/components/ui/sidebar"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useDocs } from "@/lib/docs/context"
import { displayName, moduleName, routes, shortPackageNames, type DocsIndex, type IndexedType, type PackageInfo } from "@/lib/docs/model"
import type { ModuleDocumentation } from "@/lib/docs/types"
import type { Route } from "@/lib/router"
import { cn } from "@/lib/utils"

export type SidebarView = "packages" | "tree" | "types"

export const SIDEBAR_WIDTH = { min: 220, max: 560, default: 288 }

const LABEL = "text-[11px] tracking-[0.08em] text-muted-foreground uppercase"

type Active = { moduleId?: string; packageName?: string; typeName?: string }

/** The module, package and type the current route points into, for highlighting and auto-expanding. */
function activeLocation(route: Route, index: DocsIndex): Active {
  if (route.page === "module") return { moduleId: route.moduleId }
  if (route.page === "package") return { moduleId: route.moduleId, packageName: route.packageName }
  if (route.page === "type") {
    const indexed = index.types.get(route.qualifiedName)
    return indexed ? { moduleId: indexed.module.id, packageName: indexed.packageName, typeName: route.qualifiedName } : {}
  }
  return {}
}

export function AppSidebar({
  route,
  onSearch,
  view,
  onViewChange,
  onResize,
}: {
  route: Route
  onSearch: () => void
  view: SidebarView
  onViewChange: (view: SidebarView) => void
  onResize: (width: number) => void
}) {
  const index = useDocs()
  const active = activeLocation(route, index)
  const root = index.root
  const multiModule = index.site.modules.length > 1
  const problems = index.site.spring?.issues.filter((i) => i.severity !== "INFO").length ?? 0
  const title = index.site.title || (root ? moduleName(root) : "Documentation")
  const license = root?.project.licenses[0]?.name
  const owner = root?.project.organizationName ?? root?.project.developers[0]?.name

  return (
    <Sidebar>
      <SidebarHeader className="gap-4 px-4 pt-6">
        <a href={routes.home()} className="flex items-center gap-2">
          <span className="font-heading text-xl font-extrabold tracking-tight text-primary">{monogram(title)}</span>
          <span className="flex min-w-0 flex-col leading-tight">
            <span className="truncate text-sm font-semibold">{title}</span>
            {root && <span className="truncate text-xs text-muted-foreground">v{root.project.version}</span>}
          </span>
        </a>
        <button
          type="button"
          onClick={onSearch}
          className="flex h-8 w-full items-center gap-2 rounded-md bg-muted px-2.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          <SearchIcon className="size-3.5" />
          <span>Search</span>
          <Kbd className="ml-auto">Ctrl K</Kbd>
        </button>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel className={LABEL}>Application</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {index.site.spring && (
                <SidebarMenuItem>
                  <SidebarMenuButton render={<a href={routes.spring()} />} isActive={route.page === "spring"}>
                    <LeafIcon />
                    <span>Spring</span>
                  </SidebarMenuButton>
                  {problems > 0 && <SidebarMenuBadge>{problems}</SidebarMenuBadge>}
                </SidebarMenuItem>
              )}
              <SidebarMenuItem>
                <SidebarMenuButton render={<a href={routes.stats()} />} isActive={route.page === "stats"}>
                  <BarChart3Icon />
                  <span>Statistics</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        {root && (
          <SidebarGroup>
            <div className="flex items-center justify-between gap-2 pr-1">
              <SidebarGroupLabel className={LABEL}>{view === "types" ? "Types" : multiModule ? "Modules" : "Packages"}</SidebarGroupLabel>
              <ViewSwitch view={view} onViewChange={onViewChange} />
            </div>
            <SidebarGroupContent>
              {view === "types" ? (
                <TypeList active={active} />
              ) : (
                <SidebarMenu>
                  {multiModule ? (
                    <ModuleNode module={root} active={active} depth={0} view={view} />
                  ) : view === "tree" ? (
                    <PackageTree module={root} active={active} />
                  ) : (
                    <PackageList module={root} active={active} />
                  )}
                </SidebarMenu>
              )}
            </SidebarGroupContent>
          </SidebarGroup>
        )}
      </SidebarContent>

      <SidebarFooter className="px-4 pb-4">
        <div className="flex flex-col gap-1 border-t px-2 pt-3 text-xs leading-relaxed text-muted-foreground">
          {license && <p>Released under the {license}.</p>}
          {owner && (
            <p>
              Copyright {root?.project.inceptionYear ? `${root.project.inceptionYear} ` : ""}
              {owner}.
            </p>
          )}
          <p>Generated by {index.site.generator}</p>
        </div>
      </SidebarFooter>
      <ResizeHandle onResize={onResize} />
    </Sidebar>
  )
}

const VIEWS: { value: SidebarView; label: string; icon: typeof ListIcon }[] = [
  { value: "packages", label: "Packages", icon: PackageIcon },
  { value: "tree", label: "Package tree with types", icon: ListTreeIcon },
  { value: "types", label: "All types A–Z", icon: ListIcon },
]

function ViewSwitch({ view, onViewChange }: { view: SidebarView; onViewChange: (view: SidebarView) => void }) {
  return (
    <ToggleGroup
      value={[view]}
      onValueChange={(value) => value[0] && onViewChange(value[0] as SidebarView)}
      size="sm"
      aria-label="Navigation view"
    >
      {VIEWS.map((v) => (
        <Tooltip key={v.value}>
          <TooltipTrigger render={<ToggleGroupItem value={v.value} aria-label={v.label} />}>
            <v.icon />
          </TooltipTrigger>
          <TooltipContent>{v.label}</TooltipContent>
        </Tooltip>
      ))}
    </ToggleGroup>
  )
}

/** Drag the sidebar edge to resize; double-click resets. The width is remembered by the app. */
function ResizeHandle({ onResize }: { onResize: (width: number) => void }) {
  const start = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault()
    const move = (e: PointerEvent) => onResize(Math.min(SIDEBAR_WIDTH.max, Math.max(SIDEBAR_WIDTH.min, e.clientX)))
    const stop = () => {
      window.removeEventListener("pointermove", move)
      window.removeEventListener("pointerup", stop)
      document.body.style.removeProperty("cursor")
      document.body.style.removeProperty("user-select")
    }
    document.body.style.cursor = "col-resize"
    document.body.style.userSelect = "none"
    window.addEventListener("pointermove", move)
    window.addEventListener("pointerup", stop)
  }
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize sidebar"
      onPointerDown={start}
      onDoubleClick={() => onResize(SIDEBAR_WIDTH.default)}
      className="absolute inset-y-0 -right-1 z-20 w-2 cursor-col-resize after:absolute after:inset-y-0 after:left-1/2 after:w-px after:bg-transparent hover:after:bg-border"
    />
  )
}

// ------------------------------------------------------------------ modules

function ModuleNode({ module, active, depth, view }: { module: ModuleDocumentation; active: Active; depth: number; view: SidebarView }) {
  const index = useDocs()
  const children = index.children.get(module.id) ?? []
  const containsActive = (m: ModuleDocumentation): boolean =>
    m.id === active.moduleId || (index.children.get(m.id) ?? []).some(containsActive)
  const Icon = children.length > 0 ? FolderTreeIcon : BoxIcon
  const isActive = active.moduleId === module.id && !active.packageName

  const content = (
    <>
      <SidebarMenuSubItem>
        <SidebarMenuSubButton href={routes.module(module.id)} isActive={isActive}>
          <span>Overview</span>
        </SidebarMenuSubButton>
      </SidebarMenuSubItem>
      {view === "tree" ? <PackageTreeItems module={module} active={active} /> : <PackageSubItems module={module} active={active} />}
      {children.map((child) => (
        <ModuleNode key={child.id} module={child} active={active} depth={depth + 1} view={view} />
      ))}
    </>
  )
  const label = (
    <>
      <Icon />
      <span className="truncate" title={module.id}>{moduleName(module)}</span>
      <ChevronRightIcon className="ml-auto transition-transform group-data-[panel-open]/trigger:rotate-90" />
    </>
  )

  if (depth === 0) {
    return (
      <Collapsible defaultOpen className="group/collapsible" render={<SidebarMenuItem />}>
        <CollapsibleTrigger className="group/trigger" render={<SidebarMenuButton isActive={isActive} />}>{label}</CollapsibleTrigger>
        <CollapsibleContent>
          <SidebarMenuSub>{content}</SidebarMenuSub>
        </CollapsibleContent>
      </Collapsible>
    )
  }
  return (
    <Collapsible defaultOpen={containsActive(module)} className="group/collapsible" render={<SidebarMenuSubItem />}>
      <CollapsibleTrigger className="group/trigger" render={<SidebarMenuSubButton render={<button type="button" />} />}>{label}</CollapsibleTrigger>
      <CollapsibleContent>
        <SidebarMenuSub>{content}</SidebarMenuSub>
      </CollapsibleContent>
    </Collapsible>
  )
}

function Dot({ on }: { on: boolean }) {
  return <span className={cn("size-1.5 shrink-0 rounded-full", on ? "bg-primary" : "bg-muted-foreground/40")} />
}

/** Flat packages under a module (the default view). */
function PackageSubItems({ module, active }: { module: ModuleDocumentation; active: Active }) {
  const index = useDocs()
  const packages = index.packages.get(module.id) ?? []
  const labels = shortPackageNames(packages.map((p) => p.name))
  return (
    <>
      {packages.map((pkg) => {
        const on = active.moduleId === module.id && active.packageName === pkg.name
        return (
          <SidebarMenuSubItem key={pkg.name}>
            <SidebarMenuSubButton href={routes.pkg(module.id, pkg.name)} isActive={on} title={pkg.name}>
              <Dot on={on} />
              <span className="truncate">{labels.get(pkg.name)}</span>
            </SidebarMenuSubButton>
          </SidebarMenuSubItem>
        )
      })}
    </>
  )
}

/** Single-module projects: packages as top-level items. */
function PackageList({ module, active }: { module: ModuleDocumentation; active: Active }) {
  const index = useDocs()
  return (
    <>
      {(index.packages.get(module.id) ?? []).map((pkg) => (
        <SidebarMenuItem key={pkg.name}>
          <SidebarMenuButton render={<a href={routes.pkg(module.id, pkg.name)} />} isActive={active.packageName === pkg.name} title={pkg.name}>
            <PackageIcon />
            <span className="truncate">{pkg.name || "(default)"}</span>
          </SidebarMenuButton>
          <SidebarMenuBadge>{pkg.types.length}</SidebarMenuBadge>
        </SidebarMenuItem>
      ))}
    </>
  )
}

// --------------------------------------------------------------- tree view

interface PackageNode {
  label: string // one or more segments, e.g. "joshuasalcedo.pretty" after compaction
  name: string // full package name of this node
  pkg?: PackageInfo
  children: PackageNode[]
}

/** Package names as a tree; chains of single-child, type-less segments are compacted (like IDEs do). */
function packageTree(packages: PackageInfo[]): PackageNode[] {
  const root: PackageNode = { label: "", name: "", children: [] }
  for (const pkg of packages) {
    let node = root
    const segments = pkg.name ? pkg.name.split(".") : ["(default)"]
    segments.forEach((segment, i) => {
      const name = pkg.name ? segments.slice(0, i + 1).join(".") : ""
      let child = node.children.find((c) => c.name === name)
      if (!child) {
        child = { label: segment, name, children: [] }
        node.children.push(child)
      }
      node = child
    })
    node.pkg = pkg
  }
  const compact = (node: PackageNode): PackageNode => {
    let current = node
    while (!current.pkg && current.children.length === 1) {
      const only = current.children[0]
      current = { ...only, label: `${current.label}.${only.label}` }
    }
    return { ...current, children: current.children.map(compact).sort((a, b) => a.label.localeCompare(b.label)) }
  }
  return root.children.map(compact)
}

function PackageTree({ module, active }: { module: ModuleDocumentation; active: Active }) {
  return (
    <SidebarMenuItem>
      <SidebarMenuSub className="mx-0 border-l-0 px-0">
        <PackageTreeItems module={module} active={active} />
      </SidebarMenuSub>
    </SidebarMenuItem>
  )
}

function PackageTreeItems({ module, active }: { module: ModuleDocumentation; active: Active }) {
  const index = useDocs()
  const tree = useMemo(() => packageTree(index.packages.get(module.id) ?? []), [index, module.id])
  return (
    <>
      {tree.map((node) => (
        <TreeNode key={node.name || node.label} node={node} module={module} active={active} />
      ))}
    </>
  )
}

function TreeNode({ node, module, active }: { node: PackageNode; module: ModuleDocumentation; active: Active }) {
  const inModule = active.moduleId === module.id
  const containsActive = inModule && active.packageName !== undefined
    && (active.packageName === node.name || active.packageName.startsWith(`${node.name}.`) || (!node.name && !active.packageName))
  const types = node.pkg?.types ?? []
  return (
    <Collapsible defaultOpen={containsActive} className="group/collapsible" render={<SidebarMenuSubItem />}>
      <CollapsibleTrigger className="group/trigger" render={<SidebarMenuSubButton render={<button type="button" />} title={node.name} />}>
        <ChevronRightIcon className="transition-transform group-data-[panel-open]/trigger:rotate-90" />
        <PackageIcon />
        <span className={cn("truncate", inModule && active.packageName === node.name && "text-primary")}>{node.label}</span>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <SidebarMenuSub>
          {node.pkg && (
            <SidebarMenuSubItem>
              <SidebarMenuSubButton href={routes.pkg(module.id, node.pkg.name)} size="sm"
                isActive={inModule && active.packageName === node.name && !active.typeName}>
                <span className="text-muted-foreground">Package overview</span>
              </SidebarMenuSubButton>
            </SidebarMenuSubItem>
          )}
          {node.children.map((child) => (
            <TreeNode key={child.name} node={child} module={module} active={active} />
          ))}
          {types.map((t) => (
            <TypeItem key={t.type.qualifiedName} indexed={t} active={active} />
          ))}
        </SidebarMenuSub>
      </CollapsibleContent>
    </Collapsible>
  )
}

function TypeItem({ indexed, active, detail }: { indexed: IndexedType; active: Active; detail?: ReactNode }) {
  return (
    <SidebarMenuSubItem>
      <SidebarMenuSubButton href={routes.type(indexed.type.qualifiedName)} isActive={active.typeName === indexed.type.qualifiedName}
        title={indexed.type.qualifiedName}>
        <TypeIcon type={indexed.type} />
        <span className="truncate">{displayName(indexed)}</span>
        {detail}
      </SidebarMenuSubButton>
    </SidebarMenuSubItem>
  )
}

// --------------------------------------------------------------- types view

const TYPE_LIMIT = 400

function TypeList({ active }: { active: Active }) {
  const index = useDocs()
  const [filter, setFilter] = useState("")
  const all = useMemo(
    () => [...index.types.values()].sort((a, b) => a.type.name.localeCompare(b.type.name) || a.type.qualifiedName.localeCompare(b.type.qualifiedName)),
    [index]
  )
  const q = filter.trim().toLowerCase()
  const shown = q ? all.filter((t) => t.type.qualifiedName.toLowerCase().includes(q)) : all
  return (
    <div className="flex flex-col gap-2">
      <SidebarInput placeholder={`Filter ${all.length} types…`} value={filter} onChange={(e) => setFilter(e.target.value)} />
      <SidebarMenu>
        <SidebarMenuItem>
          <SidebarMenuSub className="mx-0 border-l-0 px-0">
            {shown.slice(0, TYPE_LIMIT).map((t) => (
              <TypeItem key={t.type.qualifiedName} indexed={t} active={active}
                detail={<span className="ml-auto truncate pl-2 text-[11px] text-muted-foreground">{t.packageName.split(".").pop()}</span>} />
            ))}
          </SidebarMenuSub>
        </SidebarMenuItem>
      </SidebarMenu>
      {shown.length > TYPE_LIMIT && (
        <p className="px-2 text-xs text-muted-foreground">{shown.length - TYPE_LIMIT} more; type to filter.</p>
      )}
    </div>
  )
}

/** Short accent mark for the project, like the template's "D3": initials of the title's words. */
function monogram(title: string) {
  const words = title.split(/[^A-Za-z0-9]+/).filter(Boolean)
  return (words.length > 1 ? words.slice(0, 2).map((w) => w[0]).join("") : (words[0] ?? "D").slice(0, 2)).toUpperCase()
}
