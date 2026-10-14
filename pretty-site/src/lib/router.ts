import { useEffect, useState } from "react"

export type Route =
  | { page: "home" }
  | { page: "module"; moduleId: string }
  | { page: "package"; moduleId: string; packageName: string }
  | { page: "type"; qualifiedName: string; member?: string }
  | { page: "spring"; tab?: string }
  | { page: "stats" }
  | { page: "not-found"; path: string }

/** Hash routes (#/m/<module>, #/m/<module>/p/<package>, #/t/<type>/<member>) so the site works from file://. */
export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeURIComponent)
  if (parts.length === 0) return { page: "home" }
  if (parts[0] === "m" && parts.length === 2) return { page: "module", moduleId: parts[1] }
  if (parts[0] === "m" && parts[2] === "p" && parts.length === 4) {
    return { page: "package", moduleId: parts[1], packageName: parts[3] === "(default)" ? "" : parts[3] }
  }
  if (parts[0] === "stats" && parts.length === 1) return { page: "stats" }
  if (parts[0] === "spring" && parts.length <= 2) return { page: "spring", tab: parts[1] }
  if (parts[0] === "t" && (parts.length === 2 || parts.length === 3)) {
    return { page: "type", qualifiedName: parts[1], member: parts[2] }
  }
  return { page: "not-found", path: hash }
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash))
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash))
    window.addEventListener("hashchange", onChange)
    return () => window.removeEventListener("hashchange", onChange)
  }, [])
  return route
}
