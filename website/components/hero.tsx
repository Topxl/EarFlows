"use client";

import { motion } from "framer-motion";
import {
  Github,
  Download,
  Headphones,
  Wifi,
  WifiOff,
  Globe,
} from "lucide-react";

export function Hero() {
  return (
    <section className="relative min-h-screen flex items-center justify-center overflow-hidden pt-16">
      {/* Background Elements */}
      <div className="absolute inset-0 overflow-hidden">
        {/* Gradient orbs */}
        <div className="absolute top-1/4 -left-32 w-96 h-96 bg-primary/20 rounded-full blur-3xl" />
        <div className="absolute bottom-1/4 -right-32 w-96 h-96 bg-accent/20 rounded-full blur-3xl" />

        {/* Grid pattern */}
        <div
          className="absolute inset-0 opacity-[0.02]"
          style={{
            backgroundImage: `linear-gradient(var(--color-foreground) 1px, transparent 1px), linear-gradient(90deg, var(--color-foreground) 1px, transparent 1px)`,
            backgroundSize: "64px 64px",
          }}
        />

        {/* Corner decorations */}
        <div className="absolute top-20 left-10 w-px h-32 bg-gradient-to-b from-transparent via-primary/50 to-transparent hidden lg:block" />
        <div className="absolute top-20 left-10 h-px w-32 bg-gradient-to-r from-transparent via-primary/50 to-transparent hidden lg:block" />
        <div className="absolute top-20 right-10 w-px h-32 bg-gradient-to-b from-transparent via-accent/50 to-transparent hidden lg:block" />
        <div className="absolute top-20 right-10 h-px w-32 bg-gradient-to-l from-transparent via-accent/50 to-transparent hidden lg:block" />
      </div>

      <div className="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-20">
        <div className="text-center">
          {/* Badge */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5 }}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-full border border-primary/30 bg-primary/5 text-primary text-sm mb-8"
          >
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-primary opacity-75"></span>
              <span className="relative inline-flex rounded-full h-2 w-2 bg-primary"></span>
            </span>
            Open Source Android App
          </motion.div>

          {/* Main Headline */}
          <motion.h1
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.1 }}
            className="text-4xl sm:text-5xl md:text-6xl lg:text-7xl font-bold tracking-tight text-balance"
          >
            Real-time Translation
            <br />
            <span className="text-primary">Through Your Earbuds</span>
          </motion.h1>

          {/* Description */}
          <motion.p
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.2 }}
            className="mt-6 text-lg sm:text-xl text-muted-foreground max-w-2xl mx-auto text-pretty"
          >
            Hear any language instantly translated in your ear. Works offline
            with AI models or ultra-fast with cloud. Perfect for travelers,
            expats, and language learners.
          </motion.p>

          {/* CTA Buttons */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.3 }}
            className="mt-10 flex flex-col sm:flex-row items-center justify-center gap-4"
          >
            <a
              href="#download"
              className="inline-flex items-center gap-2 px-6 py-3 bg-primary text-primary-foreground rounded-lg font-medium hover:bg-primary/90 transition-all hover:scale-105"
            >
              <Download className="w-5 h-5" />
              Download APK
            </a>
            <a
              href="https://github.com/Topxl/EarFlows"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 px-6 py-3 border border-border rounded-lg font-medium hover:bg-secondary transition-all"
            >
              <Github className="w-5 h-5" />
              View on GitHub
            </a>
          </motion.div>

          {/* Quick Stats */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.4 }}
            className="mt-16 grid grid-cols-2 md:grid-cols-4 gap-6 max-w-3xl mx-auto"
          >
            <QuickStat
              icon={<Headphones className="w-5 h-5" />}
              label="Bluetooth"
              value="Earbuds"
            />
            <QuickStat
              icon={<WifiOff className="w-5 h-5" />}
              label="Works"
              value="Offline"
            />
            <QuickStat
              icon={<Wifi className="w-5 h-5" />}
              label="Cloud"
              value="Ultra-fast"
            />
            <QuickStat
              icon={<Globe className="w-5 h-5" />}
              label="Languages"
              value="10+"
            />
          </motion.div>
        </div>

        {/* App Preview */}
        <motion.div
          initial={{ opacity: 0, y: 40 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: 0.5 }}
          className="mt-20 relative"
        >
          <div className="relative max-w-sm mx-auto">
            {/* Phone Frame */}
            <div className="relative bg-card rounded-[3rem] p-3 border border-border shadow-2xl">
              {/* Screen */}
              <div className="bg-background rounded-[2.5rem] overflow-hidden aspect-[9/19] relative">
                {/* Status bar */}
                <div className="absolute top-0 left-0 right-0 h-12 bg-background flex items-center justify-center">
                  <div className="w-20 h-6 bg-muted rounded-full" />
                </div>

                {/* App Content */}
                <div className="pt-16 px-6 pb-8 h-full flex flex-col">
                  {/* App Header */}
                  <div className="flex items-center justify-between mb-8">
                    <h3 className="text-lg font-bold">EarFlows</h3>
                    <div className="w-8 h-8 rounded-full bg-muted flex items-center justify-center">
                      <div className="w-4 h-4 bg-muted-foreground/50 rounded" />
                    </div>
                  </div>

                  {/* Language Pair */}
                  <div className="bg-muted/50 rounded-xl p-4 mb-8">
                    <div className="flex items-center justify-center gap-4 text-sm font-medium">
                      <span>Thai</span>
                      <span className="text-primary">→</span>
                      <span>French</span>
                    </div>
                  </div>

                  {/* Main Button */}
                  <div className="flex-1 flex items-center justify-center">
                    <div className="relative">
                      <div className="absolute inset-0 bg-primary/20 rounded-full animate-ping" />
                      <div className="relative w-32 h-32 bg-primary/20 rounded-full flex items-center justify-center border-2 border-primary">
                        <Headphones className="w-12 h-12 text-primary" />
                      </div>
                    </div>
                  </div>

                  {/* Status */}
                  <p className="text-center text-muted-foreground text-sm mb-4">
                    Listening...
                  </p>

                  {/* Mode Toggle */}
                  <div className="bg-muted/50 rounded-xl p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Wifi className="w-4 h-4 text-primary" />
                        <span className="text-sm">Cloud (ultra-fast)</span>
                      </div>
                      <div className="w-10 h-6 bg-primary rounded-full relative">
                        <div className="absolute right-1 top-1 w-4 h-4 bg-white rounded-full" />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Floating elements */}
            <motion.div
              animate={{ y: [0, -10, 0] }}
              transition={{ duration: 3, repeat: Infinity, ease: "easeInOut" }}
              className="absolute -right-16 top-20 hidden lg:block"
            >
              <div className="bg-card border border-border rounded-lg px-4 py-2 shadow-lg">
                <div className="flex items-center gap-2">
                  <div className="w-2 h-2 bg-primary rounded-full animate-pulse" />
                  <span className="text-sm text-muted-foreground">
                    Voice detected
                  </span>
                </div>
              </div>
            </motion.div>

            <motion.div
              animate={{ y: [0, 10, 0] }}
              transition={{
                duration: 3,
                repeat: Infinity,
                ease: "easeInOut",
                delay: 1,
              }}
              className="absolute -left-16 bottom-32 hidden lg:block"
            >
              <div className="bg-card border border-border rounded-lg px-4 py-2 shadow-lg">
                <div className="flex items-center gap-2">
                  <Globe className="w-4 h-4 text-accent" />
                  <span className="text-sm text-muted-foreground">
                    Translating...
                  </span>
                </div>
              </div>
            </motion.div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}

function QuickStat({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="flex flex-col items-center gap-2 p-4 rounded-xl bg-card/50 border border-border/50">
      <div className="text-primary">{icon}</div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="font-semibold">{value}</div>
    </div>
  );
}
