'use client';

export default function Error({ reset }: { error: Error; reset: () => void }) {
  return (
    <div className="wrap" style={{ padding: '64px 20px' }}>
      <p className="kicker">Busy moment</p>
      <h1 className="display" style={{ fontSize: 48 }}>We&apos;re a little overwhelmed</h1>
      <p className="muted">The store didn&apos;t answer in time. If you were in line, your place is kept.</p>
      <button className="btn" onClick={reset}>Try again</button>
    </div>
  );
}
