import Link from 'next/link';

export default function NotFound() {
  return (
    <div className="wrap" style={{ padding: '64px 20px' }}>
      <p className="kicker">404</p>
      <h1 className="display" style={{ fontSize: 48 }}>Nothing here</h1>
      <p className="muted">That drop doesn&apos;t exist or hasn&apos;t been announced yet.</p>
      <Link className="btn" href="/">See the drops</Link>
    </div>
  );
}
