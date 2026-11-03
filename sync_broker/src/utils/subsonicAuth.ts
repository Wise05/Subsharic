import crypto from 'crypto';

export interface SubsonicParams {
  u: string;
  t: string;
  s: string;
  v: string;
  c: string;
  f: string;
}

export function getSubsonicAuthParams(): SubsonicParams {
  const user = process.env.NAVIDROME_USER || '';
  const password = process.env.NAVIDROME_PASS || '';
  const salt = crypto.randomBytes(8).toString('hex');

  // t = MD5(password + salt)
  const token = crypto
    .createHash('md5')
    .update(password + salt)
    .digest('hex');

  return {
    u: user,
    t: token,
    s: salt,
    v: '1.16.1',
    c: 'SoundParty',
    f: 'json'
  };
}
