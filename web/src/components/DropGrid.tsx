import Link from 'next/link';
import type { DropView } from '@/lib/api';
import { money } from '@/lib/format';
import { LocalTime } from '@/components/LocalTime';

/** The catalogue grid. Plain markup, so both the cached server page and the client fallback can render it. */
export function DropGrid({ drops }: { drops: DropView[] }) {
  if (drops.length === 0) return <p className="muted">No drops right now. Check back soon.</p>;
  return (
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
              {d.skus.length > 0 && <>from {money(Math.min(...d.skus.map((s) => s.priceMinor)), d.skus[0].currency)} · </>}
              {d.phase === 'ENDED' ? 'ended' : d.phase === 'LIVE'
                ? <>on now · closes <LocalTime iso={d.closesAt} /></>
                : <>opens <LocalTime iso={d.opensAt} /></>}
            </span>
          </div>
        </Link>
      ))}
    </div>
  );
}

function PhaseBadge({ phase }: { phase: DropView['phase'] }) {
  const label = { UPCOMING: 'Upcoming', QUEUE_OPEN: 'Line open', LIVE: 'Live', ENDED: 'Ended' }[phase];
  return <span className={`badge ${phase === 'LIVE' ? 'live' : phase === 'ENDED' ? 'sold' : ''}`}>{label}</span>;
}
