'use client';

import { useEffect, useState } from 'react';

/** Renders a time in the shopper's own time zone (the server doesn't know it). */
export function LocalTime({ iso }: { iso: string }) {
  const [text, setText] = useState(() => new Date(iso).toISOString().slice(0, 16).replace('T', ' ') + ' UTC');
  useEffect(() => {
    setText(new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }));
  }, [iso]);
  return <time dateTime={iso}>{text}</time>;
}
