import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: `npm run dev` serves the app and proxies API calls to the Spring backend
// on :8080, so you run the backend (`./gradlew bootRun`) and this side by side.
// Build: `npm run build` bundles straight into Spring's static dir, so a plain
// `./gradlew bootRun` serves the compiled UI at http://localhost:8080.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/documents': 'http://localhost:8080',
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
});
