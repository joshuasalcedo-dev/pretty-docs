import { MoonIcon, SunIcon } from "lucide-react"
import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from "react"

import { AppSidebar, SIDEBAR_WIDTH, type SidebarView } from "@/components/docs/app-sidebar"
import { SearchDialog } from "@/components/docs/search-dialog"
import { useTheme } from "@/components/theme-provider"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"
import { Button } from "@/components/ui/button"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty"
import { Separator } from "@/components/ui/separator"
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar"
import { TooltipProvider } from "@/components/ui/tooltip"
import { DocsContext } from "@/lib/docs/context"
import { buildIndex, displayName, moduleName, routes, type DocsIndex } from "@/lib/docs/model"
import { SUPPORTED_SCHEMA_VERSION } from "@/lib/docs/types"
import { useRoute, type Route } from "@/lib/router"
import { ModulePage } from "@/pages/module-page"
import { PackagePage } from "@/pages/package-page"
import { SpringPage } from "@/pages/spring-page"
import { StatsPage } from "@/pages/stats-page"
import { TypePage } from "@/pages/type-page"

export function App() {
  const site = window.__PRETTY_DOCS__
  const index = useMemo(() => (site ? buildIndex(site) : null), [site])

  if (!site || !index) {
    return (
      <Notice title="No documentation data">
        <code>docs-data.js</code> was not found next to this page. Generate it with{" "}
        <code>mvn io.joshuasalcedo.pretty:pretty-docs-maven-plugin:generate</code>.
      </Notice>
    )
  }
  if (site.schemaVersion !== SUPPORTED_SCHEMA_VERSION) {
    return (
      <Notice title="Unsupported documentation format">
        The data was written with schema version {site.schemaVersion}, but this site reads version{" "}
        {SUPPORTED_SCHEMA_VERSION}. Regenerate the docs with a matching plugin version.
      </Notice>
    )
  }

  return (
    <DocsContext.Provider value={index}>
      <TooltipProvider>
        <Docs index={index} />
      </TooltipProvider>
    </DocsContext.Provider>
  )
}

function Docs({ index }: { index: DocsIndex }) {
  const route = useRoute()
  const [searchOpen, setSearchOpen] = useState(false)
  const [width, setWidth] = useStoredState("pretty-docs.sidebar-width", SIDEBAR_WIDTH.default)
  const [view, setView] = useStoredState<SidebarView>("pretty-docs.sidebar-view", "packages")

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.key === "k" && (e.metaKey || e.ctrlKey)) || (e.key === "/" && !isTyping(e.target))) {
        e.preventDefault()
        setSearchOpen((open) => !open)
      }
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [])

  useEffect(() => {
    document.title = pageTitle(route, index)
  }, [route, index])

  return (
    <SidebarProvider style={{ "--sidebar-width": `${width}px` } as CSSProperties}>
      <AppSidebar route={route} onSearch={() => setSearchOpen(true)} view={view} onViewChange={setView} onResize={setWidth} />
      <SidebarInset>
        <header className="sticky top-0 z-10 flex h-14 shrink-0 items-center gap-2 border-b bg-background/95 px-4 backdrop-blur">
          <SidebarTrigger className="-ml-1" />
          <Separator orientation="vertical" className="mr-2 data-vertical:h-4 data-vertical:self-auto" />
          <Crumbs route={route} index={index} />
          <ThemeToggle />
        </header>
        <main className="mx-auto w-full max-w-6xl px-6 py-8 md:px-10">
          <Page route={route} index={index} />
        </main>
      </SidebarInset>
      <SearchDialog open={searchOpen} onOpenChange={setSearchOpen} />
    </SidebarProvider>
  )
}

function Page({ route, index }: { route: Route; index: DocsIndex }) {
  switch (route.page) {
    case "home":
      return index.root ? <ModulePage module={index.root} home /> : null
    case "module": {
      const module = index.modules.get(route.moduleId)
      if (!module) break
      return module.id === index.root?.id ? <ModulePage module={module} home /> : <ModulePage module={module} />
    }
    case "package": {
      const pkg = index.packages.get(route.moduleId)?.find((p) => p.name === route.packageName)
      if (!pkg) break
      return <PackagePage pkg={pkg} />
    }
    case "type": {
      const indexed = index.types.get(route.qualifiedName)
      if (!indexed) break
      return <TypePage indexed={indexed} member={route.member} />
    }
    case "spring":
      return <SpringPage tab={route.tab} />
    case "stats":
      return <StatsPage />
  }
  return (
    <Empty>
      <EmptyHeader>
        <EmptyTitle>Page not found</EmptyTitle>
        <EmptyDescription>Nothing is documented at this address. It may be below the documented visibility.</EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button render={<a href={routes.home()} />} variant="outline">
          Back to overview
        </Button>
      </EmptyContent>
    </Empty>
  )
}

function Crumbs({ route, index }: { route: Route; index: DocsIndex }) {
  const items: { label: string; href?: string }[] = []
  const addModule = (id: string) => {
    const module = index.modules.get(id)
    if (module && module.id !== index.root?.id) items.push({ label: moduleName(module), href: routes.module(id) })
  }
  if (route.page === "module") addModule(route.moduleId)
  if (route.page === "spring") items.push({ label: "Spring" })
  if (route.page === "stats") items.push({ label: "Statistics" })
  if (route.page === "package") {
    addModule(route.moduleId)
    items.push({ label: route.packageName || "(default)" })
  }
  if (route.page === "type") {
    const indexed = index.types.get(route.qualifiedName)
    if (indexed) {
      addModule(indexed.module.id)
      items.push({ label: indexed.packageName || "(default)", href: routes.pkg(indexed.module.id, indexed.packageName) })
      items.push({ label: displayName(indexed) })
    }
  }
  if (items.length > 0) delete items[items.length - 1].href

  return (
    <Breadcrumb className="min-w-0 flex-1">
      <BreadcrumbList className="flex-nowrap">
        <BreadcrumbItem>
          {items.length === 0 ? <BreadcrumbPage>Overview</BreadcrumbPage> : <BreadcrumbLink href={routes.home()}>Overview</BreadcrumbLink>}
        </BreadcrumbItem>
        {items.map((item) => (
          <span key={item.label} className="contents">
            <BreadcrumbSeparator />
            <BreadcrumbItem className="min-w-0">
              {item.href ? (
                <BreadcrumbLink href={item.href} className="truncate">{item.label}</BreadcrumbLink>
              ) : (
                <BreadcrumbPage className="truncate">{item.label}</BreadcrumbPage>
              )}
            </BreadcrumbItem>
          </span>
        ))}
      </BreadcrumbList>
    </Breadcrumb>
  )
}

function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  const dark =
    theme === "dark" || (theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches)
  return (
    <Button variant="ghost" size="icon" onClick={() => setTheme(dark ? "light" : "dark")} aria-label="Toggle theme">
      {dark ? <SunIcon /> : <MoonIcon />}
    </Button>
  )
}

function Notice({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex min-h-svh items-center justify-center p-6">
      <Empty>
        <EmptyHeader>
          <EmptyTitle>{title}</EmptyTitle>
          <EmptyDescription>{children}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    </div>
  )
}

function pageTitle(route: Route, index: DocsIndex) {
  const site = index.site.title || (index.root ? moduleName(index.root) : "Documentation")
  if (route.page === "type") {
    const indexed = index.types.get(route.qualifiedName)
    if (indexed) return `${displayName(indexed)} · ${site}`
  }
  if (route.page === "spring") return `Spring · ${site}`
  if (route.page === "stats") return `Statistics · ${site}`
  if (route.page === "package") return `${route.packageName || "(default)"} · ${site}`
  if (route.page === "module") {
    const module = index.modules.get(route.moduleId)
    if (module) return `${moduleName(module)} · ${site}`
  }
  return site
}

function isTyping(target: EventTarget | null) {
  return target instanceof HTMLElement && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName))
}

export default App

/** State remembered per browser (sidebar width and view); falls back to the default where storage is unavailable. */
function useStoredState<T>(key: string, fallback: T): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = window.localStorage.getItem(key)
      return stored === null ? fallback : (JSON.parse(stored) as T)
    } catch {
      return fallback
    }
  })
  const update = (next: T) => {
    setValue(next)
    try {
      window.localStorage.setItem(key, JSON.stringify(next))
    } catch {
      // private mode or blocked storage: keep the in-memory value
    }
  }
  return [value, update]
}
