"use client";

import { motion } from "framer-motion";
import { Bluetooth, Download, Headphones, Play } from "lucide-react";

const steps = [
  {
    number: "01",
    icon: <Download className="w-6 h-6" />,
    title: "Download the App",
    description:
      "Get the APK from GitHub releases or build from source. Install on any Android device.",
  },
  {
    number: "02",
    icon: <Bluetooth className="w-6 h-6" />,
    title: "Connect Earbuds",
    description:
      "Pair your Bluetooth earbuds with your phone. Any standard Bluetooth audio device works.",
  },
  {
    number: "03",
    icon: <Headphones className="w-6 h-6" />,
    title: "Download Models",
    description:
      "For offline mode, download AI models (about 2GB). Or skip this and use cloud mode.",
  },
  {
    number: "04",
    icon: <Play className="w-6 h-6" />,
    title: "Start Translating",
    description:
      "Tap the button, and hear translations in real-time directly through your earbuds.",
  },
];

export function HowItWorks() {
  return (
    <section id="how-it-works" className="py-24 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Section Header */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5 }}
          className="text-center mb-16"
        >
          <h2 className="text-3xl sm:text-4xl font-bold mb-4">How It Works</h2>
          <p className="text-muted-foreground max-w-2xl mx-auto text-lg">
            Get started in minutes with these simple steps
          </p>
        </motion.div>

        {/* Steps */}
        <div className="relative">
          {/* Connecting Line */}
          <div className="absolute left-8 md:left-1/2 top-0 bottom-0 w-px bg-gradient-to-b from-primary via-accent to-primary/20 hidden sm:block" />

          <div className="space-y-12 md:space-y-0 md:grid md:grid-cols-1 md:gap-0">
            {steps.map((step, index) => (
              <motion.div
                key={index}
                initial={{ opacity: 0, x: index % 2 === 0 ? -20 : 20 }}
                whileInView={{ opacity: 1, x: 0 }}
                viewport={{ once: true }}
                transition={{ duration: 0.5, delay: index * 0.1 }}
                className={`relative flex items-center gap-8 ${
                  index % 2 === 0 ? "md:flex-row" : "md:flex-row-reverse"
                }`}
              >
                {/* Content */}
                <div
                  className={`flex-1 ${
                    index % 2 === 0 ? "md:text-right md:pr-16" : "md:text-left md:pl-16"
                  }`}
                >
                  <div
                    className={`inline-flex flex-col ${
                      index % 2 === 0 ? "md:items-end" : "md:items-start"
                    }`}
                  >
                    <span className="text-xs text-primary font-mono mb-2">
                      STEP {step.number}
                    </span>
                    <h3 className="text-xl font-semibold mb-2">{step.title}</h3>
                    <p className="text-muted-foreground max-w-sm">
                      {step.description}
                    </p>
                  </div>
                </div>

                {/* Center Icon */}
                <div className="absolute left-4 md:left-1/2 md:-translate-x-1/2 w-8 h-8 md:w-12 md:h-12 rounded-full bg-card border border-border flex items-center justify-center z-10">
                  <div className="text-primary">{step.icon}</div>
                </div>

                {/* Spacer for alignment */}
                <div className="flex-1 hidden md:block" />
              </motion.div>
            ))}
          </div>
        </div>

        {/* Demo Video Placeholder */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5 }}
          className="mt-20"
        >
          <div className="relative aspect-video max-w-4xl mx-auto rounded-2xl overflow-hidden bg-card border border-border">
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="text-center">
                <div className="w-20 h-20 rounded-full bg-primary/10 flex items-center justify-center mx-auto mb-4 cursor-pointer hover:bg-primary/20 transition-colors">
                  <Play className="w-8 h-8 text-primary ml-1" />
                </div>
                <p className="text-muted-foreground">Watch Demo Video</p>
              </div>
            </div>
            {/* Decorative gradient */}
            <div className="absolute inset-0 bg-gradient-to-t from-background/50 to-transparent pointer-events-none" />
          </div>
        </motion.div>
      </div>
    </section>
  );
}
