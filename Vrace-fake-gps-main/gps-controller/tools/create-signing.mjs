// Create one retained local identity for this original app; print only its public fingerprint.
import { generateKeyPairSync, randomBytes, sign, X509Certificate } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

function der(tag, bytes) {
  let length = Buffer.from([bytes.length]);
  if (bytes.length >= 128) {
    let hex = bytes.length.toString(16); if (hex.length % 2) hex = '0' + hex;
    const size = Buffer.from(hex, 'hex'); length = Buffer.concat([Buffer.from([128 | size.length]), size]);
  }
  return Buffer.concat([Buffer.from([tag]), length, bytes]);
}
const seq = (...values) => der(0x30, Buffer.concat(values));
const integer = bytes => der(2, bytes[0] & 128 ? Buffer.concat([Buffer.from([0]), bytes]) : bytes);
const file = resolve(process.argv[2] || '.tools/gps-controller-signing/identity.json');
if (!existsSync(file)) {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const algorithm = Buffer.from('300d06092a864886f70d01010b0500', 'hex');
  const name = seq(der(0x31, seq(Buffer.from('0603550403', 'hex'), der(0x0c, Buffer.from('GPS Controller Local Test')))));
  const time = date => der(0x17, Buffer.from(date.toISOString().replace(/[-:]/g, '').slice(2, 15).replace('T', '') + 'Z'));
  const start = new Date(Date.now() - 86400000), end = new Date(start); end.setUTCFullYear(end.getUTCFullYear() + 20);
  const tbs = seq(der(0xa0, integer(Buffer.from([2]))), integer(randomBytes(16)), algorithm, name,
    seq(time(start), time(end)), name, publicKey.export({ type: 'spki', format: 'der' }));
  const certificate = seq(tbs, algorithm, der(3, Buffer.concat([Buffer.from([0]), sign('sha256', tbs, privateKey)])));
  if (!new X509Certificate(certificate).verify(publicKey)) throw new Error('Certificate verification failed');
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify({ privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    certificate: certificate.toString('base64') }), { flag: 'wx', mode: 0o600 });
}
const identity = JSON.parse(readFileSync(file, 'utf8'));
console.log('Controller package: vn.stationary.route');
console.log('Certificate SHA-1: ' + new X509Certificate(Buffer.from(identity.certificate, 'base64')).fingerprint);
console.log('Retained private identity: ' + file);
