import { useEffect, useSyncExternalStore } from "react"

import type { ModuleDocumentation } from "./types"

/*
 * docs-data.js holds an outline of every module; each module's full sources are in their own
 * script (module.dataScript), added to the page the first time one of its pages opens. Script tags
 * rather than fetch() keep the site working from file://. Loading swaps the module's files in place
 * and bumps a version, so the index is rebuilt with the full types.
 */

let version = 0
const listeners = new Set<() => void>()
const loading = new Map<string, Promise<void>>()
const failed = new Set<string>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function changed() {
  version++
  listeners.forEach((l) => l())
}

/** Changes whenever a module's data arrives; rebuild what was derived from the site. */
export function useModuleDataVersion() {
  return useSyncExternalStore(subscribe, () => version)
}

function apply(module: ModuleDocumentation) {
  const data = window.__PRETTY_DOCS_MODULES__?.[module.id]
  if (!data) return false
  module.files = data.files
  module.testFiles = data.testFiles
  delete module.dataScript
  return true
}

export function loadModule(module: ModuleDocumentation): Promise<void> {
  if (!module.dataScript) return Promise.resolve()
  if (apply(module)) {
    changed()
    return Promise.resolve()
  }
  const src = module.dataScript
  let promise = loading.get(module.id)
  if (!promise) {
    promise = new Promise<void>((resolve, reject) => {
      const script = document.createElement("script")
      script.src = `./${src}`
      script.onload = () => (apply(module) ? resolve() : reject(new Error(`${src} did not define ${module.id}`)))
      script.onerror = () => reject(new Error(`Could not load ${src}`))
      document.head.appendChild(script)
    })
      .then(changed)
      .catch((error) => {
        failed.add(module.id)
        loading.delete(module.id)
        changed()
        throw error
      })
    loading.set(module.id, promise)
  }
  return promise
}

/** Loads the modules' data; true once all of them are in, "failed" when one could not be loaded. */
export function useModules(modules: (ModuleDocumentation | undefined)[]): boolean | "failed" {
  useModuleDataVersion()
  const present = modules.filter((m): m is ModuleDocumentation => m !== undefined)
  const key = present.map((m) => m.id).join("|")
  useEffect(() => {
    present.forEach((m) => loadModule(m).catch((error) => console.error("[pretty-docs]", error)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  if (present.some((m) => failed.has(m.id))) return "failed"
  return present.every((m) => !m.dataScript)
}
