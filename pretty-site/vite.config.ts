import { resolve } from "node:path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"
import { viteSingleFile } from "vite-plugin-singlefile"

// The template is a single self-contained index.html (JS/CSS/fonts inlined) so the generated
// site opens straight from disk; module scripts and fetch() are blocked on file:// URLs.
export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss(), viteSingleFile()],
  resolve: {
    alias: {
      "@": resolve(import.meta.dirname, "./src"),
    },
  },
  build: {
    outDir: "target/classes/pretty-site",
    emptyOutDir: true,
  },
})
