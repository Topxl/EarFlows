import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // IMPORTANT: The root `app/` directory is the Android project, NOT Next.js App Router.
  // Next.js source is in `src/app/`. Setting srcDir is not needed because
  // Next.js auto-detects src/ when it exists, BUT only if there's no root app/ directory.
  // Since we can't rename the Android app/ folder, we use distDir to avoid conflicts.
  distDir: ".next",
};

export default nextConfig;
