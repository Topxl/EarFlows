"use client";

import { motion } from "framer-motion";
import {
  Headphones,
  Wifi,
  WifiOff,
  Zap,
  Shield,
  Globe,
  Mic,
  Volume2,
  RefreshCcw,
} from "lucide-react";

const features = [
  {
    icon: <Headphones className="w-6 h-6" />,
    title: "Bluetooth Earbuds",
    description:
      "Connect any Bluetooth earbuds and hear translations directly in your ear while the conversation happens.",
    color: "primary",
  },
  {
    icon: <WifiOff className="w-6 h-6" />,
    title: "Works Offline",
    description:
      "Download AI models (SeamlessM4T, Whisper, NLLB) and translate without internet connection.",
    color: "accent",
  },
  {
    icon: <Wifi className="w-6 h-6" />,
    title: "Cloud Mode",
    description:
      "Use OpenAI Realtime API for ultra-fast, high-quality translations when connected to internet.",
    color: "primary",
  },
  {
    icon: <Mic className="w-6 h-6" />,
    title: "Voice Activity Detection",
    description:
      "Smart detection of speech using Silero VAD. Only translates when someone is speaking.",
    color: "accent",
  },
  {
    icon: <Zap className="w-6 h-6" />,
    title: "Low Latency",
    description:
      "Optimized for real-time conversations with minimal delay between speech and translation.",
    color: "primary",
  },
  {
    icon: <RefreshCcw className="w-6 h-6" />,
    title: "Auto-Fallback",
    description:
      "Automatically switches between cloud and local engines if connection drops.",
    color: "accent",
  },
];

const container = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: {
      staggerChildren: 0.1,
    },
  },
};

const item = {
  hidden: { opacity: 0, y: 20 },
  show: { opacity: 1, y: 0 },
};

export function Features() {
  return (
    <section id="features" className="py-24 relative">
      {/* Background */}
      <div className="absolute inset-0 bg-gradient-to-b from-background via-muted/20 to-background" />

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
            Powerful Features
          </h2>
          <p className="text-muted-foreground max-w-2xl mx-auto text-lg">
            Everything you need for seamless real-time translation, whether
            you're online or off the grid.
          </p>
        </motion.div>

        {/* Features Grid */}
        <motion.div
          variants={container}
          initial="hidden"
          whileInView="show"
          viewport={{ once: true }}
          className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6"
        >
          {features.map((feature, index) => (
            <motion.div
              key={index}
              variants={item}
              className="group relative p-6 rounded-2xl bg-card border border-border hover:border-primary/50 transition-all duration-300"
            >
              {/* Icon */}
              <div
                className={`w-12 h-12 rounded-xl flex items-center justify-center mb-4 ${
                  feature.color === "primary"
                    ? "bg-primary/10 text-primary"
                    : "bg-accent/10 text-accent"
                }`}
              >
                {feature.icon}
              </div>

              {/* Content */}
              <h3 className="text-lg font-semibold mb-2">{feature.title}</h3>
              <p className="text-muted-foreground text-sm leading-relaxed">
                {feature.description}
              </p>

              {/* Hover glow */}
              <div className="absolute inset-0 rounded-2xl bg-gradient-to-br from-primary/5 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none" />
            </motion.div>
          ))}
        </motion.div>

        {/* AI Engines Section */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5 }}
          className="mt-20"
        >
          <div className="bg-card rounded-2xl border border-border p-8 md:p-12">
            <div className="grid md:grid-cols-2 gap-8 items-center">
              <div>
                <h3 className="text-2xl font-bold mb-4">
                  Multiple AI Engines
                </h3>
                <p className="text-muted-foreground mb-6">
                  EarFlows uses state-of-the-art AI models with automatic
                  fallback to ensure you always have translation available.
                </p>
                <div className="space-y-4">
                  <EngineItem
                    name="SeamlessStreaming"
                    description="Meta's real-time speech-to-speech translation"
                    badge="Best Quality"
                  />
                  <EngineItem
                    name="Cascade Pipeline"
                    description="Whisper STT + NLLB Translation + TTS"
                    badge="Offline"
                  />
                  <EngineItem
                    name="OpenAI Realtime"
                    description="Cloud-powered ultra-fast translation"
                    badge="Fastest"
                  />
                </div>
              </div>
              <div className="relative">
                <div className="bg-muted rounded-xl p-6 font-mono text-sm">
                  <div className="flex items-center gap-2 text-muted-foreground mb-4">
                    <div className="w-3 h-3 rounded-full bg-destructive/50" />
                    <div className="w-3 h-3 rounded-full bg-yellow-500/50" />
                    <div className="w-3 h-3 rounded-full bg-primary/50" />
                  </div>
                  <pre className="text-xs overflow-x-auto">
                    <code className="text-muted-foreground">
                      {`// Translation Chain
1. Cloud (OpenAI) ─┐
   └─ if offline ──┘
2. SeamlessM4T ────┐
   └─ if unavailable ─┘
3. Cascade (Whisper+NLLB)

✓ Always have fallback
✓ Best quality available
✓ Auto-switch on failure`}
                    </code>
                  </pre>
                </div>
              </div>
            </div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}

function EngineItem({
  name,
  description,
  badge,
}: {
  name: string;
  description: string;
  badge: string;
}) {
  return (
    <div className="flex items-start gap-3">
      <div className="w-2 h-2 rounded-full bg-primary mt-2 flex-shrink-0" />
      <div>
        <div className="flex items-center gap-2">
          <span className="font-medium">{name}</span>
          <span className="text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary">
            {badge}
          </span>
        </div>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
    </div>
  );
}
