"use client";

import { motion } from "framer-motion";
import { Github, GitPullRequest, Bug, MessageSquare, Star } from "lucide-react";

const contributions = [
  {
    icon: <Bug className="w-5 h-5" />,
    title: "Report Bugs",
    description: "Found an issue? Open a GitHub issue with details.",
  },
  {
    icon: <GitPullRequest className="w-5 h-5" />,
    title: "Submit PRs",
    description: "Fix bugs or add features. All contributions welcome.",
  },
  {
    icon: <MessageSquare className="w-5 h-5" />,
    title: "Discussions",
    description: "Share ideas and feedback in GitHub Discussions.",
  },
  {
    icon: <Star className="w-5 h-5" />,
    title: "Star the Repo",
    description: "Show your support by starring the project.",
  },
];

export function Contribute() {
  return (
    <section id="contribute" className="py-24 relative">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="relative rounded-3xl overflow-hidden">
          {/* Background gradient */}
          <div className="absolute inset-0 bg-gradient-to-br from-primary/10 via-card to-accent/10" />
          <div className="absolute inset-0 border border-border rounded-3xl" />

          <div className="relative z-10 p-8 md:p-16">
            <div className="grid lg:grid-cols-2 gap-12 items-center">
              {/* Left Content */}
              <motion.div
                initial={{ opacity: 0, x: -20 }}
                whileInView={{ opacity: 1, x: 0 }}
                viewport={{ once: true }}
                transition={{ duration: 0.5 }}
              >
                <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full border border-primary/30 bg-primary/5 text-primary text-sm mb-6">
                  <Github className="w-4 h-4" />
                  Open Source
                </div>
                <h2 className="text-3xl sm:text-4xl font-bold mb-4">
                  Built by the Community,
                  <br />
                  <span className="text-primary">For the Community</span>
                </h2>
                <p className="text-muted-foreground mb-8 text-lg">
                  EarFlows is completely open source. Join us in building the
                  best real-time translation app for Android.
                </p>

                <div className="flex flex-col sm:flex-row gap-4">
                  <a
                    href="https://github.com/Topxl/EarFlows"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center justify-center gap-2 px-6 py-3 bg-foreground text-background rounded-lg font-medium hover:bg-foreground/90 transition-all"
                  >
                    <Github className="w-5 h-5" />
                    View Repository
                  </a>
                  <a
                    href="https://github.com/Topxl/EarFlows/issues"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center justify-center gap-2 px-6 py-3 border border-border rounded-lg font-medium hover:bg-secondary transition-all"
                  >
                    <Bug className="w-5 h-5" />
                    Open Issues
                  </a>
                </div>
              </motion.div>

              {/* Right Content - Ways to Contribute */}
              <motion.div
                initial={{ opacity: 0, x: 20 }}
                whileInView={{ opacity: 1, x: 0 }}
                viewport={{ once: true }}
                transition={{ duration: 0.5, delay: 0.1 }}
                className="grid grid-cols-2 gap-4"
              >
                {contributions.map((item, index) => (
                  <div
                    key={index}
                    className="p-4 rounded-xl bg-card/50 border border-border/50 hover:border-primary/30 transition-colors"
                  >
                    <div className="w-10 h-10 rounded-lg bg-primary/10 text-primary flex items-center justify-center mb-3">
                      {item.icon}
                    </div>
                    <h3 className="font-medium mb-1 text-sm">{item.title}</h3>
                    <p className="text-xs text-muted-foreground">
                      {item.description}
                    </p>
                  </div>
                ))}
              </motion.div>
            </div>

            {/* Tech Stack */}
            <motion.div
              initial={{ opacity: 0, y: 20 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true }}
              transition={{ duration: 0.5, delay: 0.2 }}
              className="mt-12 pt-8 border-t border-border/50"
            >
              <p className="text-sm text-muted-foreground mb-4 text-center">
                Built with modern technologies
              </p>
              <div className="flex flex-wrap items-center justify-center gap-4">
                <TechBadge name="Kotlin" />
                <TechBadge name="Jetpack Compose" />
                <TechBadge name="ONNX Runtime" />
                <TechBadge name="Whisper" />
                <TechBadge name="NLLB" />
                <TechBadge name="Silero VAD" />
              </div>
            </motion.div>
          </div>
        </div>
      </div>
    </section>
  );
}

function TechBadge({ name }: { name: string }) {
  return (
    <span className="px-3 py-1.5 rounded-lg bg-muted/50 border border-border/50 text-sm text-muted-foreground">
      {name}
    </span>
  );
}
