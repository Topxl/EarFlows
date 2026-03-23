"use client";

import { motion } from "framer-motion";
import { Download, Github, Terminal, Smartphone } from "lucide-react";

export function DownloadSection() {
  return (
    <section id="download" className="py-24 relative">
      {/* Background */}
      <div className="absolute inset-0 bg-gradient-to-t from-muted/20 to-background" />

      <div className="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Section Header */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5 }}
          className="text-center mb-16"
        >
          <h2 className="text-3xl sm:text-4xl font-bold mb-4">
            Get EarFlows Now
          </h2>
          <p className="text-muted-foreground max-w-2xl mx-auto text-lg">
            Download the APK directly or build from source. Free and open source
            forever.
          </p>
        </motion.div>

        {/* Download Options */}
        <div className="grid md:grid-cols-2 gap-6 max-w-4xl mx-auto">
          {/* APK Download */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.5 }}
            className="relative p-8 rounded-2xl bg-card border border-border hover:border-primary/50 transition-all group"
          >
            <div className="absolute top-4 right-4">
              <span className="px-2 py-1 text-xs bg-primary/10 text-primary rounded-full">
                Recommended
              </span>
            </div>
            <div className="w-14 h-14 rounded-xl bg-primary/10 text-primary flex items-center justify-center mb-6">
              <Download className="w-7 h-7" />
            </div>
            <h3 className="text-xl font-semibold mb-2">Download APK</h3>
            <p className="text-muted-foreground mb-6">
              Get the latest release directly from GitHub. Install on any
              Android device with Android 8.0 or higher.
            </p>
            <a
              href="https://github.com/Topxl/EarFlows/releases/latest"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center justify-center gap-2 w-full px-6 py-3 bg-primary text-primary-foreground rounded-lg font-medium hover:bg-primary/90 transition-all"
            >
              <Smartphone className="w-5 h-5" />
              Download Latest APK
            </a>
          </motion.div>

          {/* Build from Source */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.5, delay: 0.1 }}
            className="relative p-8 rounded-2xl bg-card border border-border hover:border-accent/50 transition-all group"
          >
            <div className="w-14 h-14 rounded-xl bg-accent/10 text-accent flex items-center justify-center mb-6">
              <Terminal className="w-7 h-7" />
            </div>
            <h3 className="text-xl font-semibold mb-2">Build from Source</h3>
            <p className="text-muted-foreground mb-6">
              Clone the repository and build with Android Studio or Gradle. Full
              control over the build.
            </p>
            <div className="space-y-3">
              <div className="bg-muted rounded-lg p-3 font-mono text-sm overflow-x-auto">
                <code className="text-muted-foreground">
                  git clone https://github.com/Topxl/EarFlows.git
                </code>
              </div>
              <div className="bg-muted rounded-lg p-3 font-mono text-sm overflow-x-auto">
                <code className="text-muted-foreground">
                  ./gradlew assembleDebug
                </code>
              </div>
            </div>
          </motion.div>
        </div>

        {/* Requirements */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5, delay: 0.2 }}
          className="mt-12 text-center"
        >
          <p className="text-sm text-muted-foreground mb-4">Requirements</p>
          <div className="flex flex-wrap items-center justify-center gap-4">
            <Requirement label="Android 8.0+" />
            <Requirement label="Bluetooth Audio" />
            <Requirement label="~2GB for offline models" />
          </div>
        </motion.div>
      </div>
    </section>
  );
}

function Requirement({ label }: { label: string }) {
  return (
    <span className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-muted/50 border border-border/50 text-sm">
      <span className="w-1.5 h-1.5 rounded-full bg-primary" />
      {label}
    </span>
  );
}
