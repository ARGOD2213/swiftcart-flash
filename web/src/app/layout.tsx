import type { Metadata } from 'next';
import Link from 'next/link';
import { AccountNav } from '@/components/AccountNav';
import './globals.css';

export const metadata: Metadata = {
  title: 'SwiftCart Flash',
  description: 'Limited drops that never oversell.',
};

// No cookies are read here: pages stay statically renderable and cacheable at the CDN. Whether the shopper is
// signed in is decided in the browser (AccountNav), and the API is the judge of every request anyway.
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="top">
          <div className="wrap">
            <Link href="/" className="brand">Swift<span>Cart</span></Link>
            <nav className="nav">
              <Link href="/">Drops</Link>
              <AccountNav />
            </nav>
          </div>
        </header>
        <main>{children}</main>
        <footer className="foot">
          <div className="wrap">SwiftCart Flash · a flash-sale reference build. Payments in the demo are simulated.</div>
        </footer>
      </body>
    </html>
  );
}
