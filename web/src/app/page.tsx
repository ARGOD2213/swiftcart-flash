import Link from 'next/link';
import { apiGet, type DropView } from '@/lib/api';
import { money } from '@/lib/format';
import { LocalTime } from '@/components/LocalTime';

// Pre-rendered and cached (ISR): the CDN serves the catalogue, the API only sees a refresh every 30 s.
export const revalidate = 30;

export default async function Home() {
  const drops = (await apiGet<DropView[]>('/api/drops', { next: { revalidate: 30 } })) ?? [];
  return (
    <div className="wrap">
      <section className="hero">
        <p className="kicker">Limited drops</p>
        <h1 className="display">One run.<br />No restocks.</h1>
        <p className="muted" style={{ maxWidth: 560 }}>
          When a drop opens you join a fair line, get a short window to check out, and your pair is held while you pay.
          Nothing is ever sold twice.
        </p>
      </section>
      {drops.length === 0 ? (
        <p className="muted">No drops right now. Check back soon.</p>
      ) : (
        <div className="grid">
          {drops.map((d) => (
            <Link key={d.slug} href={`/drops/${d.slug}`} className="card">
              <div className="art">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={d.imageUrl ?? '/images/midnight-runner.svg'} alt="" />
              </div>
              <div className="body">
                <PhaseBadge phase={d.phase} />
                <h2>{d.name}</h2>
                <span className="muted">
                  from {money(Math.min(...d.skus.map((s) => s.priceMinor)), d.skus[0]?.currency)} ·{' '}
                  {d.phase === 'ENDED' ? 'ended' : <>opens <LocalTime iso={d.opensAt} /></>}
                </span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

function PhaseBadge({ phase }: { phase: DropView['phase'] }) {
  const label = { UPCOMING: 'Upcoming', QUEUE_OPEN: 'Line open', LIVE: 'Live', ENDED: 'Ended' }[phase];
  return <span className={`badge ${phase === 'LIVE' ? 'live' : phase === 'ENDED' ? 'sold' : ''}`}>{label}</span>;
}
