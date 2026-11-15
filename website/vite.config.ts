import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("/node_modules/@firebase/auth/")) return "firebase-auth";
          if (id.includes("/node_modules/@firebase/firestore/")) return "firebase-firestore";
          if (id.includes("/node_modules/@firebase/webchannel-wrapper/") || id.includes("/node_modules/@grpc/") || id.includes("/node_modules/protobufjs/") || id.includes("/node_modules/@protobufjs/") || id.includes("/node_modules/long/")) return "firebase-transport";
          if (id.includes("/node_modules/@firebase/app/")) return "firebase-core";
          if (id.includes("/node_modules/firebase/")) return "firebase";
          if (id.includes("/node_modules/leaflet/") || id.includes("/node_modules/react-leaflet/")) return "map";
          if (id.includes("/node_modules/react/") || id.includes("/node_modules/scheduler/")) return "react";
        },
      },
    },
  },
});
