import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, unlinkSync, rmdirSync } from 'node:fs';
import { join } from 'node:path';
import { generateIdentity, localIdentity, signApk, verifyApk, zipLayout } from './apk_v2.mjs';
import { createHash } from 'node:crypto';

// Independently produced with Python's zipfile: one stored entry containing "local fixture".
const fixture = Buffer.from('UEsDBBQAAAAAAHE1S11fxkDtDQAAAA0AAAAIAAAAdGVzdC50eHRsb2NhbCBmaXh0dXJlUEsBAhQAFAAAAAAAcTVLXV/GQO0NAAAADQAAAAgAAAAAAAAAAAAAAIABAAAAAHRlc3QudHh0UEsFBgAAAAABAAEANgAAADMAAAAAAA==', 'base64');
const identity = generateIdentity();

test('v2 signing preserves ZIP contents and cryptographically verifies', () => {
  const signed = signApk(fixture, identity), layout = zipLayout(signed);
  assert.equal(verifyApk(signed).verified, true);
  assert.equal(verifyApk(signed).signers[0].subject, 'CN=vRace Local Test');
  const fingerprint = createHash('sha1').update(identity.certificate).digest('hex').match(/../g).join(':').toUpperCase();
  assert.equal(verifyApk(signed).signers[0].certificateSha1, fingerprint);
  assert.ok(signed.subarray(0, zipLayout(fixture).central).equals(fixture.subarray(0, zipLayout(fixture).central)));
  assert.equal(layout.entries, 1);
});
test('modified entry bytes are rejected', () => {
  const signed = signApk(fixture, identity);
  signed[signed.indexOf(Buffer.from('local fixture'))] ^= 1;
  assert.throws(() => verifyApk(signed), /content digest mismatch/);
});
test('modified central directory bytes are rejected', () => {
  const signed = signApk(fixture, identity);
  signed[zipLayout(signed).central + 46] ^= 1;
  assert.throws(() => verifyApk(signed), /content digest mismatch/);
});
test('modified signed certificate bytes are rejected', () => {
  const signed = signApk(fixture, identity);
  signed[signed.indexOf(Buffer.from('vRace Local Test'))] ^= 1;
  assert.throws(() => verifyApk(signed), /signer signature mismatch/);
});
test('EOCD comment is protected by the digest', () => {
  const unsigned = Buffer.concat([fixture, Buffer.from('abc')]);
  unsigned.writeUInt16LE(3, fixture.length - 2);
  const signed = signApk(unsigned, identity);
  assert.equal(verifyApk(signed).verified, true);
  signed[signed.length - 1] ^= 1;
  assert.throws(() => verifyApk(signed), /content digest mismatch/);
});
test('content spanning multiple 1 MiB chunks is signed and tampering is detected', () => {
  const original = zipLayout(fixture), padding = Buffer.alloc(1049000);
  const unsigned = Buffer.concat([fixture.subarray(0, original.central), padding, fixture.subarray(original.central)]);
  unsigned.writeUInt32LE(original.central + padding.length, original.end + padding.length + 16);
  const signed = signApk(unsigned, identity);
  assert.equal(verifyApk(signed).verified, true);
  signed[1048577] ^= 1;
  assert.throws(() => verifyApk(signed), /content digest mismatch/);
});
test('a mismatched signing key and certificate cannot produce a verified APK', () => {
  assert.throws(() => signApk(fixture, { ...identity, privateKey: generateIdentity().privateKey }), /signature mismatch/);
});
test('already signed input and truncated ZIP are rejected', () => {
  assert.throws(() => signApk(signApk(fixture, identity), identity), /unsigned archive/);
  assert.throws(() => signApk(fixture.subarray(0, 40), identity), /end record not found/);
});
test('the local certificate is retained for repeat builds', () => {
  mkdirSync('.tools', { recursive: true });
  const directory = mkdtempSync(join('.tools', 'identity-test-'));
  try {
    const first = localIdentity(directory), second = localIdentity(directory);
    assert.ok(first.certificate.equals(second.certificate));
    assert.equal(first.privateKey, second.privateKey);
  } finally {
    unlinkSync(join(directory, 'identity.json'));
    rmdirSync(directory);
  }
});
