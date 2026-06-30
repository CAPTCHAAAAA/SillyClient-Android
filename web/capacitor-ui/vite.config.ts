import viteReact from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";
import { defineConfig } from "vite";

export default defineConfig({
  base: "./",
  plugins: [viteReact(), viteSingleFile()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});