import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  // `npm run dev` talks to the API started by Docker Compose on :8080.
  server: { proxy: { '/api': 'http://localhost:8080' } },
});
