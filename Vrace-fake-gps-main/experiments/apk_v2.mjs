// APK Signature Scheme v2, following the AOSP format. No external dependencies.
// This tool only signs local files; it never installs APKs or contacts a service.
import { createHash, createPublicKey, generateKeyPairSync, randomBytes, sign, verify,
  constants, X509Certificate } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const MAGIC = Buffer.from('APK Sig Block 42');
const V2_ID = 0x7109871a;
const ALG = 0x0103; // RSA PKCS#1 v1.5 with SHA-256
const check = (condition, message) => { if (!condition) throw new Error(message); };
const u32 = value => { const b = Buffer.alloc(4); b.writeUInt32LE(value); return b; };
const u64 = value => { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(value)); return b; };
const lp = value => Buffer.concat([u32(value.length), value]);
const hash = (value, algorithm = 'sha256') => createHash(algorithm).update(value).digest();

class Reader {
  constructor(buffer) { this.buffer = buffer; this.offset = 0; }
  take(size) {
    check(Number.isSafeInteger(size) && size >= 0 && this.offset + size <= this.buffer.length, 'Truncated signing field');
    const result = this.buffer.subarray(this.offset, this.offset + size);
    this.offset += size;
    return result;
  }
  word() { return this.take(4).readUInt32LE(); }
  field() { return this.take(this.word()); }
  done() { check(this.offset === this.buffer.length, 'Trailing signing data'); }
}

function records(buffer) {
  const reader = new Reader(buffer), output = [];
  while (reader.offset < buffer.length) output.push(reader.field());
  return output;
}

function algorithmRecords(buffer) {
  return records(buffer).map(record => {
    const reader = new Reader(record);
    const id = reader.word(), data = reader.field();
    reader.done();
    return { id, data };
  });
}

export function zipLayout(buffer) {
  for (let end = buffer.length - 22; end >= Math.max(0, buffer.length - 65557); end--) {
    if (buffer.readUInt32LE(end) !== 0x06054b50 || end + 22 + buffer.readUInt16LE(end + 20) !== buffer.length) continue;
    check(buffer.readUInt16LE(end + 4) === 0 && buffer.readUInt16LE(end + 6) === 0, 'Multi-disk ZIP is unsupported');
    const entries = buffer.readUInt16LE(end + 10);
    check(entries > 0 && entries !== 0xffff && entries === buffer.readUInt16LE(end + 8), 'Invalid ZIP entry count');
    const central = buffer.readUInt32LE(end + 16), size = buffer.readUInt32LE(end + 12);
    check(central + size === end && central < end && buffer.readUInt32LE(central) === 0x02014b50, 'Invalid ZIP central directory');
    return { central, end, entries };
  }
  throw new Error('ZIP end record not found');
}

function signingBlock(buffer, central) {
  check(central >= 32 && buffer.subarray(central - 16, central).equals(MAGIC), 'APK signing block not found');
  const size = Number(buffer.readBigUInt64LE(central - 24));
  check(Number.isSafeInteger(size) && size >= 24 && size + 8 <= central, 'Invalid APK signing block size');
  const start = central - size - 8;
  check(buffer.readBigUInt64LE(start) === BigInt(size), 'APK signing block sizes differ');
  const pairs = new Map();
  let offset = start + 8;
  while (offset < central - 24) {
    check(offset + 8 <= central - 24, 'Truncated signing pair');
    const length = Number(buffer.readBigUInt64LE(offset));
    offset += 8;
    check(Number.isSafeInteger(length) && length >= 4 && offset + length <= central - 24, 'Invalid signing pair length');
    const id = buffer.readUInt32LE(offset);
    check(!pairs.has(id), 'Duplicate signing block ID');
    pairs.set(id, buffer.subarray(offset + 4, offset + length));
    offset += length;
  }
  check(offset === central - 24, 'Invalid signing block alignment');
  return { start, pairs };
}

function contentDigest(sections, algorithm = 'sha256') {
  const chunks = [];
  for (const section of sections) {
    for (let offset = 0; offset < section.length; offset += 1048576) {
      const chunk = section.subarray(offset, Math.min(offset + 1048576, section.length));
      chunks.push(hash(Buffer.concat([Buffer.from([0xa5]), u32(chunk.length), chunk]), algorithm));
    }
  }
  return hash(Buffer.concat([Buffer.from([0x5a]), u32(chunks.length), ...chunks]), algorithm);
}

function verificationOptions(id, publicKey) {
  const algorithm = [0x0102, 0x0104, 0x0202].includes(id) ? 'sha512' : 'sha256';
  if (id === 0x0101 || id === 0x0102) {
    return { algorithm, key: { key: publicKey, padding: constants.RSA_PKCS1_PSS_PADDING, saltLength: id === 0x0101 ? 32 : 64 } };
  }
  check([0x0103, 0x0104, 0x0201, 0x0202, 0x0301].includes(id), 'Unsupported signature algorithm');
  return { algorithm, key: publicKey };
}

export function verifyApk(buffer) {
  const layout = zipLayout(buffer), block = signingBlock(buffer, layout.central);
  check(block.pairs.has(V2_ID), 'APK v2 signature not found');
  const root = new Reader(block.pairs.get(V2_ID));
  const signers = records(root.field());
  root.done();
  check(signers.length > 0, 'APK has no signers');
  const eocd = Buffer.from(buffer.subarray(layout.end));
  eocd.writeUInt32LE(block.start, 16);
  const identities = signers.map(value => {
    const reader = new Reader(value);
    const signedData = reader.field(), signatures = algorithmRecords(reader.field()), publicBytes = reader.field();
    reader.done();
    const publicKey = createPublicKey({ key: publicBytes, type: 'spki', format: 'der' });
    const signature = signatures.find(record => [0x0101, 0x0102, 0x0103, 0x0104, 0x0201, 0x0202, 0x0301].includes(record.id));
    check(signature, 'No supported signature');
    const options = verificationOptions(signature.id, publicKey);
    check(verify(options.algorithm, signedData, options.key, signature.data), 'APK signer signature mismatch');
    const data = new Reader(signedData), digests = algorithmRecords(data.field()), certificates = records(data.field());
    const attributes = records(data.field());
    // The store-signed inputs contain a final zero word after attributes.
    // Android's v2 reader does not require signedData to end at attributes.
    if (data.offset < signedData.length) {
      check(data.take(signedData.length - data.offset).equals(Buffer.alloc(4)), 'Unsupported signed-data padding');
    }
    data.done();
    check(JSON.stringify(digests.map(record => record.id)) === JSON.stringify(signatures.map(record => record.id)), 'Signature/digest algorithms differ');
    for (const attribute of attributes) {
      check(attribute.length >= 4, 'Truncated signature attribute');
      if (attribute.readUInt32LE(0) === 0xbeeff00d) {
        check(attribute.length === 8, 'Invalid stripping-protection attribute');
        if (attribute.readUInt32LE(4) === 3) check(block.pairs.has(0xf05368c0), 'Required v3 signature was stripped');
      }
    }
    const digest = contentDigest([buffer.subarray(0, block.start), buffer.subarray(layout.central, layout.end), eocd], options.algorithm);
    check(digests.find(record => record.id === signature.id)?.data.equals(digest), 'APK content digest mismatch');
    check(certificates.length > 0, 'Missing signer certificate');
    const certificate = new X509Certificate(certificates[0]);
    check(certificate.publicKey.export({ type: 'spki', format: 'der' }).equals(publicBytes), 'Certificate/public key mismatch');
    return { certificateSha256: hash(certificates[0]).toString('hex'), certificateSha1: certificate.fingerprint,
      subject: certificate.subject, algorithm: signature.id };
  });
  return { verified: true, scheme: 'v2', apkSha256: hash(buffer).toString('hex'), bytes: buffer.length, signers: identities };
}

function der(tag, value) {
  let size;
  if (value.length < 128) size = Buffer.from([value.length]);
  else {
    let hex = value.length.toString(16);
    if (hex.length % 2) hex = `0${hex}`;
    const bytes = Buffer.from(hex, 'hex');
    size = Buffer.concat([Buffer.from([0x80 | bytes.length]), bytes]);
  }
  return Buffer.concat([Buffer.from([tag]), size, value]);
}
const seq = (...values) => der(0x30, Buffer.concat(values));
const integer = bytes => der(0x02, bytes[0] & 0x80 ? Buffer.concat([Buffer.from([0]), bytes]) : bytes);

export function generateIdentity() {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const algorithm = seq(Buffer.from('06092a864886f70d01010b0500', 'hex'));
  const name = seq(der(0x31, seq(Buffer.from('0603550403', 'hex'), der(0x0c, Buffer.from('vRace Local Test')))));
  const time = date => der(0x17, Buffer.from(date.toISOString().replace(/[-:]/g, '').slice(2, 15).replace('T', '') + 'Z'));
  const start = new Date(Date.now() - 86400000), end = new Date(start);
  end.setUTCFullYear(end.getUTCFullYear() + 10);
  const tbs = seq(der(0xa0, integer(Buffer.from([2]))), integer(randomBytes(16)), algorithm, name,
    seq(time(start), time(end)), name, publicKey.export({ type: 'spki', format: 'der' }));
  const certificate = seq(tbs, algorithm, der(0x03, Buffer.concat([Buffer.from([0]), sign('sha256', tbs, privateKey)])));
  const parsed = new X509Certificate(certificate);
  check(parsed.verify(publicKey), 'Generated certificate signature is invalid');
  return { privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' }), certificate };
}

export function localIdentity(directory) {
  mkdirSync(directory, { recursive: true });
  const path = join(directory, 'identity.json');
  if (!existsSync(path)) {
    const identity = generateIdentity();
    writeFileSync(path, JSON.stringify({ privateKey: identity.privateKey, certificate: identity.certificate.toString('base64') }), { flag: 'wx', mode: 0o600 });
  }
  const identity = JSON.parse(readFileSync(path, 'utf8'));
  return { privateKey: identity.privateKey, certificate: Buffer.from(identity.certificate, 'base64') };
}

export function signApk(unsigned, identity) {
  const { central, end } = zipLayout(unsigned);
  check(!unsigned.subarray(central - 16, central).equals(MAGIC), 'Input must be a rebuilt unsigned archive');
  const certificate = new X509Certificate(identity.certificate);
  const publicBytes = certificate.publicKey.export({ type: 'spki', format: 'der' });
  const digest = contentDigest([unsigned.subarray(0, central), unsigned.subarray(central, end), unsigned.subarray(end)]);
  const signedData = Buffer.concat([lp(lp(Buffer.concat([u32(ALG), lp(digest)]))), lp(lp(identity.certificate)), lp(Buffer.alloc(0))]);
  const signature = sign('sha256', signedData, identity.privateKey);
  const signer = Buffer.concat([lp(signedData), lp(lp(Buffer.concat([u32(ALG), lp(signature)]))), lp(publicBytes)]);
  const value = lp(lp(signer));
  const pair = Buffer.concat([u64(4 + value.length), u32(V2_ID), value]);
  const block = Buffer.concat([u64(pair.length + 24), pair, u64(pair.length + 24), MAGIC]);
  const eocd = Buffer.from(unsigned.subarray(end));
  eocd.writeUInt32LE(central + block.length, 16);
  const result = Buffer.concat([unsigned.subarray(0, central), block, unsigned.subarray(central, end), eocd]);
  verifyApk(result);
  return result;
}

function main() {
  const [mode, ...args] = process.argv.slice(2);
  if (mode === 'verify') {
    check(args.length > 0, 'Provide one or more APK paths');
    console.log(JSON.stringify(args.map(path => ({ file: path, ...verifyApk(readFileSync(path)) })), null, 2));
  } else if (mode === 'sign') {
    const [inputDir, outputDir, keyDir] = args;
    check(args.length === 3, 'Usage: node experiments/apk_v2.mjs sign INPUT_DIR OUTPUT_DIR KEY_DIR');
    const report = JSON.parse(readFileSync(join(inputDir, 'patch-report.json'), 'utf8'));
    const identity = localIdentity(keyDir), outputs = [];
    mkdirSync(outputDir, { recursive: true });
    for (const name of Object.keys(report.input)) {
      check(!name.includes('/') && !name.includes('\\') && name.endsWith('.apk'), 'Invalid APK name');
      const signed = signApk(readFileSync(join(inputDir, name)), identity);
      writeFileSync(join(outputDir, name), signed);
      outputs.push({ file: name, ...verifyApk(readFileSync(join(outputDir, name))) });
    }
    check(new Set(outputs.map(item => item.signers[0].certificateSha256)).size === 1, 'Split signing certificates differ');
    report.outputs = outputs;
    report.signature_verification = 'Local v2 cryptographic verification; Android installer not tested';
    writeFileSync(join(outputDir, 'build-report.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ outputs, device_tested: false, backend_tested: false }, null, 2));
  } else throw new Error('Use verify APK... or sign INPUT_DIR OUTPUT_DIR KEY_DIR');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
