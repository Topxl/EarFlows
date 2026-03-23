"use client";

import { Headphones, Github, Twitter, Heart } from "lucide-react";

const footerLinks = {
  project: [
    { name: "Features", href: "#features" },
    { name: "How it Works", href: "#how-it-works" },
    { name: "Languages", href: "#languages" },
    { name: "Download", href: "#download" },
  ],
  resources: [
    {
      name: "GitHub",
      href: "https://github.com/Topxl/EarFlows",
      external: true,
    },
    {
      name: "Releases",
      href: "https://github.com/Topxl/EarFlows/releases",
      external: true,
    },
    {
      name: "Issues",
      href: "https://github.com/Topxl/EarFlows/issues",
      external: true,
    },
    {
      name: "Discussions",
      href: "https://github.com/Topxl/EarFlows/discussions",
      external: true,
    },
  ],
  legal: [
    { name: "MIT License", href: "#" },
    { name: "Privacy Policy", href: "#" },
  ],
};

export function Footer() {
  return (
    <footer className="border-t border-border bg-card/50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        {/* Main Footer */}
        <div className="py-12 grid grid-cols-2 md:grid-cols-4 gap-8">
          {/* Brand */}
          <div className="col-span-2 md:col-span-1">
            <a href="#" className="flex items-center gap-2 mb-4">
              <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
                <Headphones className="w-5 h-5 text-primary" />
              </div>
              <span className="text-lg font-semibold">EarFlows</span>
            </a>
            <p className="text-sm text-muted-foreground mb-4">
              Open source real-time audio translation for Android.
            </p>
            <div className="flex items-center gap-3">
              <a
                href="https://github.com/Topxl/EarFlows"
                target="_blank"
                rel="noopener noreferrer"
                className="w-9 h-9 rounded-lg bg-muted flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-muted/80 transition-colors"
                aria-label="GitHub"
              >
                <Github className="w-5 h-5" />
              </a>
              <a
                href="https://twitter.com"
                target="_blank"
                rel="noopener noreferrer"
                className="w-9 h-9 rounded-lg bg-muted flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-muted/80 transition-colors"
                aria-label="Twitter"
              >
                <Twitter className="w-5 h-5" />
              </a>
            </div>
          </div>

          {/* Project Links */}
          <div>
            <h4 className="font-semibold mb-4">Project</h4>
            <ul className="space-y-2">
              {footerLinks.project.map((link) => (
                <li key={link.name}>
                  <a
                    href={link.href}
                    className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                  >
                    {link.name}
                  </a>
                </li>
              ))}
            </ul>
          </div>

          {/* Resources Links */}
          <div>
            <h4 className="font-semibold mb-4">Resources</h4>
            <ul className="space-y-2">
              {footerLinks.resources.map((link) => (
                <li key={link.name}>
                  <a
                    href={link.href}
                    target={link.external ? "_blank" : undefined}
                    rel={link.external ? "noopener noreferrer" : undefined}
                    className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                  >
                    {link.name}
                  </a>
                </li>
              ))}
            </ul>
          </div>

          {/* Legal Links */}
          <div>
            <h4 className="font-semibold mb-4">Legal</h4>
            <ul className="space-y-2">
              {footerLinks.legal.map((link) => (
                <li key={link.name}>
                  <a
                    href={link.href}
                    className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                  >
                    {link.name}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {/* Bottom Bar */}
        <div className="py-6 border-t border-border flex flex-col sm:flex-row items-center justify-between gap-4">
          <p className="text-sm text-muted-foreground">
            © {new Date().getFullYear()} EarFlows. Open source under MIT
            License.
          </p>
          <p className="text-sm text-muted-foreground flex items-center gap-1">
            Made with <Heart className="w-4 h-4 text-destructive" /> by the
            community
          </p>
        </div>
      </div>
    </footer>
  );
}
