import { Header } from "@/components/header";
import { Hero } from "@/components/hero";
import { Features } from "@/components/features";
import { HowItWorks } from "@/components/how-it-works";
import { Languages } from "@/components/languages";
import { Contribute } from "@/components/contribute";
import { DownloadSection } from "@/components/download";
import { Footer } from "@/components/footer";

export default function Home() {
  return (
    <main className="min-h-screen">
      <Header />
      <Hero />
      <Features />
      <HowItWorks />
      <Languages />
      <Contribute />
      <DownloadSection />
      <Footer />
    </main>
  );
}
