import { ImageOffIcon, SearchIcon } from "lucide-react"
import { useMemo, useState } from "react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useDocs } from "@/lib/docs/context"
import { formatNumber, moduleName, routes } from "@/lib/docs/model"
import type { Asset, ModuleDocumentation } from "@/lib/docs/types"
import { cn } from "@/lib/utils"

type Backdrop = "checker" | "light" | "dark"
const PER_MODULE = 60

const extension = (asset: Asset) => asset.path.slice(asset.path.lastIndexOf(".") + 1).toLowerCase()
const fileName = (asset: Asset) => asset.path.slice(asset.path.lastIndexOf("/") + 1)
const folder = (asset: Asset) => (asset.path.includes("/") ? asset.path.slice(0, asset.path.lastIndexOf("/")) : ".")

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** Every module's images in one place: icons, logos and public/ or static/ files. */
export function AssetsPage() {
  const { site } = useDocs()
  const modules = useMemo(() => site.modules.filter((m) => (m.assets?.length ?? 0) > 0), [site])
  const formats = useMemo(() => [...new Set(modules.flatMap((m) => m.assets!.map(extension)))].sort(), [modules])
  const [shown, setShown] = useState<string[]>(formats)
  const [query, setQuery] = useState("")
  const [backdrop, setBackdrop] = useState<Backdrop>("checker")
  const total = modules.reduce((sum, m) => sum + m.assets!.length, 0)

  const q = query.trim().toLowerCase()
  const filtered = modules
    .map((module) => ({
      module,
      assets: module.assets!.filter((a) => shown.includes(extension(a))
        && (!q || `${a.path} ${moduleName(module)}`.toLowerCase().includes(q))),
    }))
    .filter((group) => group.assets.length > 0)

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <Badge variant="secondary" className="w-fit">Assets</Badge>
        <h1 className="font-heading text-4xl font-bold tracking-tight">Assets</h1>
        <p className="max-w-3xl font-serif text-lg leading-8">
          {formatNumber(total)} images from {formatNumber(modules.length)} {modules.length === 1 ? "module" : "modules"}: icons, logos
          and the files in public/, assets/ and static/ folders. Click one to open it full size.
        </p>
      </header>

      {total === 0 ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon"><ImageOffIcon /></EmptyMedia>
            <EmptyTitle>No images found</EmptyTitle>
            <EmptyDescription>No svg, png, ico, jpg, gif or webp files under 1 MB in the modules' directories.</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <section className="flex flex-col gap-6">
          <div className="flex flex-wrap items-center gap-3">
            <ToggleGroup multiple value={shown} onValueChange={(v) => setShown(v as string[])} variant="outline" size="sm">
              {formats.map((f) => (
                <ToggleGroupItem key={f} value={f}>{f}</ToggleGroupItem>
              ))}
            </ToggleGroup>
            <BackdropSwitch value={backdrop} onChange={setBackdrop} />
            <InputGroup className="max-w-xs">
              <InputGroupInput placeholder="Filter by name or folder…" value={query} onChange={(e) => setQuery(e.target.value)} />
              <InputGroupAddon>
                <SearchIcon />
              </InputGroupAddon>
            </InputGroup>
          </div>
          {filtered.length === 0 && <p className="text-sm text-muted-foreground">No images match.</p>}
          {filtered.map(({ module, assets }) => (
            <ModuleAssets key={module.id} module={module} assets={assets} backdrop={backdrop} />
          ))}
        </section>
      )}
    </div>
  )
}

function ModuleAssets({ module, assets, backdrop }: { module: ModuleDocumentation; assets: Asset[]; backdrop: Backdrop }) {
  const [limit, setLimit] = useState(PER_MODULE)
  return (
    <section className="flex flex-col gap-3">
      <h2 className="flex items-baseline gap-2">
        <a href={routes.module(module.id)} className="font-heading text-lg font-semibold text-primary underline-offset-4 hover:underline">
          {moduleName(module)}
        </a>
        <span className="font-mono text-xs text-muted-foreground">{module.relativePath || "."} · {formatNumber(assets.length)}</span>
      </h2>
      <AssetGrid assets={assets.slice(0, limit)} backdrop={backdrop} />
      {assets.length > limit && (
        <Button variant="outline" size="sm" className="w-fit" onClick={() => setLimit(assets.length)}>
          Show all {formatNumber(assets.length)}
        </Button>
      )}
    </section>
  )
}

export function BackdropSwitch({ value, onChange }: { value: Backdrop; onChange: (value: Backdrop) => void }) {
  return (
    <ToggleGroup value={[value]} onValueChange={(v) => v[0] && onChange(v[0] as Backdrop)} variant="outline" size="sm" aria-label="Background">
      <ToggleGroupItem value="checker">Checker</ToggleGroupItem>
      <ToggleGroupItem value="light">Light</ToggleGroupItem>
      <ToggleGroupItem value="dark">Dark</ToggleGroupItem>
    </ToggleGroup>
  )
}

/** Thumbnails of images; used on the Assets page and on module pages. */
export function AssetGrid({ assets, backdrop = "checker" }: { assets: Asset[]; backdrop?: Backdrop }) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(8.5rem,1fr))] gap-3">
      {assets.map((asset) => (
        <AssetTile key={asset.href} asset={asset} backdrop={backdrop} />
      ))}
    </div>
  )
}

function AssetTile({ asset, backdrop }: { asset: Asset; backdrop: Backdrop }) {
  const [size, setSize] = useState<string>()
  const [broken, setBroken] = useState(false)
  return (
    <a
      href={asset.href}
      target="_blank"
      rel="noreferrer"
      title={asset.path}
      className="group flex min-w-0 flex-col overflow-hidden rounded-md border transition-colors hover:border-primary"
    >
      <div className={cn("flex aspect-square items-center justify-center p-3", `asset-backdrop-${backdrop}`)}>
        {broken ? (
          <ImageOffIcon className="size-6 text-muted-foreground" />
        ) : (
          <img
            src={asset.href}
            alt={fileName(asset)}
            loading="lazy"
            decoding="async"
            className="max-h-full max-w-full object-contain"
            onLoad={(e) => setSize(`${e.currentTarget.naturalWidth}×${e.currentTarget.naturalHeight}`)}
            onError={() => setBroken(true)}
          />
        )}
      </div>
      <div className="flex min-w-0 flex-col gap-0.5 border-t px-2 py-1.5">
        <span className="truncate font-mono text-xs font-medium">{fileName(asset)}</span>
        <span className="truncate font-mono text-[11px] text-muted-foreground">{folder(asset)}</span>
        <span className="text-[11px] text-muted-foreground tabular-nums">
          {extension(asset)} · {formatBytes(asset.size)}{size ? ` · ${size}` : ""}
        </span>
      </div>
    </a>
  )
}
