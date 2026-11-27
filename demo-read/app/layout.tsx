import type { ReactNode } from "react";
import "./globals.css";

export const metadata = {
  title: "Agente trading — ambiente Demo",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="it">
      <body>{children}</body>
    </html>
  );
}
