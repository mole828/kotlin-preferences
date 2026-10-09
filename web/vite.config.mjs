import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

const chatProxy = {
  '/api': {
    target: 'http://127.0.0.1:8080',
    changeOrigin: true,
  },
};

export default defineConfig({
  resolve: {
    // Ktor also includes a Node-only dynamic import of ws in its JS engine.
    alias: { ws: fileURLToPath(new URL('./scripts/browser-websocket.mjs', import.meta.url)) },
  },
  server: { proxy: chatProxy },
  preview: { proxy: chatProxy },
});
