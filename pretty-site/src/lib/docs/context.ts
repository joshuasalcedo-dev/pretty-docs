import { createContext, useContext } from "react"

import type { DocsIndex } from "./model"

export const DocsContext = createContext<DocsIndex | null>(null)

export function useDocs(): DocsIndex {
  const index = useContext(DocsContext)
  if (!index) throw new Error("useDocs must be used inside DocsContext")
  return index
}
