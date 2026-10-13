// A readable "signed in" hint for the UI. The real token stays in an httpOnly cookie the page can't read.
export const SIGNED_IN_COOKIE = 'sc_signed_in';

export function signedIn(): boolean {
  if (typeof document === 'undefined') return false;
  return document.cookie.split('; ').some((c) => c === `${SIGNED_IN_COOKIE}=1`);
}
