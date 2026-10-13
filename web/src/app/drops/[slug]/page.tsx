import { notFound } from 'next/navigation';
import { apiGet, type DropView } from '@/lib/api';
import { money } from '@/lib/format';
import { DropLive } from '@/components/DropLive';

// The product page is cached like a static page (ISR, 30 s); everything live (stock, the line, checkout)
// happens in the browser against the API, so the crowd hitting refresh is served by the CDN.
export const revalidate = 30;

// No drop is rendered at build time (the API isn't there); each one is rendered on its first visit, then cached.
export async function generateStaticParams() {
  return [];
}

export default async function DropPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const drop = await apiGet<DropView>(`/api/drops/${encodeURIComponent(slug)}`, { next: { revalidate: 30 } });
  if (!drop) notFound();
  const from = Math.min(...drop.skus.map((s) => s.priceMinor));
  return (
    <div className="wrap drop">
      <div className="stage">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={drop.imageUrl ?? '/images/midnight-runner.svg'} alt={drop.name} />
      </div>
      <div className="side">
        <div>
          <p className="kicker">Limited drop · {drop.perCustomerLimit} per customer</p>
          <h1 className="display">{drop.name}</h1>
        </div>
        <div className="price">{money(from, drop.skus[0]?.currency)}</div>
        {drop.description && <p className="muted">{drop.description}</p>}
        <DropLive drop={drop} />
      </div>
    </div>
  );
}
