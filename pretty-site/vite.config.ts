import { resolve } from "node:path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"
import { viteSingleFile } from "vite-plugin-singlefile"

// The template is a single self-contained index.html (JS/CSS/fonts inlined) so the generated
// site opens straight from disk; module scripts and fetch() are blocked on file:// URLs.
// It is built into the plugin's resources and committed there: building the plugin (and using it)
// needs no Node.js. Run `npm run build` after changing the site, and commit the result.
export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss(), viteSingleFile()],
  resolve: {
    alias: {
      "@": resolve(import.meta.dirname, "./src"),
    },
  },
  build: {
    outDir: resolve(import.meta.dirname, "../pretty-docs-maven-plugin/src/main/resources/pretty-site"),
    emptyOutDir: true,
  },
})
