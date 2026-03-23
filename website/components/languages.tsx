"use client";

import { motion } from "framer-motion";

const languages = [
  { code: "THA", name: "Thai", flag: "TH" },
  { code: "FRA", name: "French", flag: "FR" },
  { code: "ENG", name: "English", flag: "GB" },
  { code: "CMN", name: "Mandarin", flag: "CN" },
  { code: "SPA", name: "Spanish", flag: "ES" },
  { code: "DEU", name: "German", flag: "DE" },
  { code: "JPN", name: "Japanese", flag: "JP" },
  { code: "KOR", name: "Korean", flag: "KR" },
  { code: "VIE", name: "Vietnamese", flag: "VN" },
  { code: "ITA", name: "Italian", flag: "IT" },
  { code: "POR", name: "Portuguese", flag: "PT" },
  { code: "RUS", name: "Russian", flag: "RU" },
];

const container = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: {
      staggerChildren: 0.05,
    },
  },
};

const item = {
  hidden: { opacity: 0, scale: 0.9 },
  show: { opacity: 1, scale: 1 },
};

export function Languages() {
  return (
    <section id="languages" className="py-24 relative">
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
            Supported Languages
          </h2>
          <p className="text-muted-foreground max-w-2xl mx-auto text-lg">
            Translate between multiple languages with high accuracy. More
            languages added regularly.
          </p>
        </motion.div>

        {/* Languages Grid */}
        <motion.div
          variants={container}
          initial="hidden"
          whileInView="show"
          viewport={{ once: true }}
          className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-4"
        >
          {languages.map((lang) => (
            <motion.div
              key={lang.code}
              variants={item}
              className="group relative p-4 rounded-xl bg-card border border-border hover:border-primary/50 transition-all duration-300 cursor-default"
            >
              <div className="flex flex-col items-center gap-2">
                {/* Flag placeholder */}
                <div className="w-10 h-10 rounded-full bg-muted flex items-center justify-center text-sm font-mono text-muted-foreground">
                  {lang.flag}
                </div>
                <span className="font-medium text-sm">{lang.name}</span>
                <span className="text-xs text-muted-foreground font-mono">
                  {lang.code}
                </span>
              </div>

              {/* Hover glow */}
              <div className="absolute inset-0 rounded-xl bg-gradient-to-br from-primary/5 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none" />
            </motion.div>
          ))}
        </motion.div>

        {/* Translation Pairs */}
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5 }}
          className="mt-12 text-center"
        >
          <p className="text-muted-foreground text-sm">
            Translate in any direction. Thai to French, English to Mandarin,
            Japanese to German, and more.
          </p>
          <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
            <TranslationPair from="Thai" to="French" />
            <TranslationPair from="English" to="Mandarin" />
            <TranslationPair from="Japanese" to="English" />
            <TranslationPair from="Korean" to="French" />
          </div>
        </motion.div>
      </div>
    </section>
  );
}

function TranslationPair({ from, to }: { from: string; to: string }) {
  return (
    <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-card border border-border text-sm">
      <span>{from}</span>
      <span className="text-primary">→</span>
      <span>{to}</span>
    </div>
  );
}
