import type { Metadata, Viewport } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: "EarFlows - Real-time Audio Translation",
  description:
    "Open source Android app for real-time audio translation through your Bluetooth earbuds. Supports offline and cloud translation with AI-powered engines.",
  keywords: [
    "translation",
    "real-time translation",
    "audio translation",
    "bluetooth",
    "earbuds",
    "android",
    "open source",
    "offline translation",
    "AI translation",
    "SeamlessM4T",
    "Whisper",
  ],
  authors: [{ name: "EarFlows Team" }],
  openGraph: {
    title: "EarFlows - Real-time Audio Translation",
    description:
      "Open source real-time audio translation through your Bluetooth earbuds",
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title: "EarFlows - Real-time Audio Translation",
    description:
      "Open source real-time audio translation through your Bluetooth earbuds",
  },
};

export const viewport: Viewport = {
  themeColor: "#00b894",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className={`${inter.variable} ${jetbrainsMono.variable}`}>
      <body className="font-sans antialiased">{children}</body>
    </html>
  );
}
