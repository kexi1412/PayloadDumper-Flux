'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const sha = buffer => crypto.createHash('sha256').update(buffer).digest();
const crcTable = Array.from({ length: 256 }, (_, value) => {
  for (let i = 0; i < 8; i++) value = (value & 1) ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});
function crc32(buffer) { let result = 0xffffffff; for (const value of buffer) result = crcTable[(result ^ value) & 255] ^ (result >>> 8); return (result ^ 0xffffffff) >>> 0; }
function varint(value) {
  let n = BigInt(value); const result = [];
  do { let byte = Number(n & 127n); n >>= 7n; if (n) byte |= 128; result.push(byte); } while (n);
  return Buffer.from(result);
}
const integer = (field, value) => Buffer.concat([varint(field << 3), varint(value)]);
const bytes = (field, value) => { const b = Buffer.isBuffer(value) ? value : Buffer.from(value); return Buffer.concat([varint((field << 3) | 2), varint(b.length), b]); };
function payload({ names = ['boot', 'vendor_boot', 'dtbo', 'vbmeta'], blocks = 1, badHash = false, delta = false, minorVersion = 0 } = {}) {
  const parts = []; const blobs = []; const expected = {}; let offset = 0;
  for (const [index, name] of names.entries()) {
    const image = Buffer.alloc(4096 * blocks, index + 65);
    Buffer.from(`Flux desktop deterministic test image: ${name}`).copy(image);
    expected[name + '.img'] = image;
    const operations = [];
    for (let block = 0; block < blocks; block++) {
      const chunk = image.subarray(block * 4096, (block + 1) * 4096);
      const extent = Buffer.concat([integer(1, block), integer(2, 1)]);
      const operation = delta
        ? Buffer.concat([integer(1, 4), bytes(4, extent), bytes(6, extent)]) // SOURCE_COPY genuinely needs old data.
        : Buffer.concat([integer(1, 0), integer(2, offset), integer(3, chunk.length), bytes(6, extent), bytes(8, badHash ? Buffer.alloc(32) : sha(chunk))]);
      operations.push(bytes(8, operation));
      if (!delta) { blobs.push(chunk); offset += chunk.length; }
    }
    const info = Buffer.concat([integer(1, image.length), bytes(2, sha(image))]);
    parts.push(bytes(13, Buffer.concat([bytes(1, name), bytes(7, info), ...operations])));
  }
  const manifest = Buffer.concat([integer(3, 4096), integer(12, minorVersion), ...parts]);
  const header = Buffer.alloc(24); header.write('CrAU'); header.writeBigUInt64BE(2n, 4); header.writeBigUInt64BE(BigInt(manifest.length), 12);
  return { buffer: Buffer.concat([header, manifest, ...blobs]), expected };
}
function zip(entries) {
  const body = []; const directory = []; let offset = 0;
  for (const { name, data, method = 0 } of entries) {
    const filename = Buffer.from(name); const content = Buffer.isBuffer(data) ? data : Buffer.from(data);
    const compressed = method === 8 ? zlib.deflateRawSync(content) : content;
    const crc = crc32(content);
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6); local.writeUInt16LE(method, 8);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(compressed.length, 18); local.writeUInt32LE(content.length, 22); local.writeUInt16LE(filename.length, 26);
    body.push(local, filename, compressed);
    const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8); central.writeUInt16LE(method, 10);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(compressed.length, 20); central.writeUInt32LE(content.length, 24); central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(offset, 42);
    directory.push(central, filename); offset += local.length + filename.length + compressed.length;
  }
  const cd = Buffer.concat(directory); const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...body, cd, end]);
}
function ota(options) {
  const result = payload(options);
  const metadata = 'ota-type=AB\npost-build=Flux/Test/fixture:16/TEST.20261001/1:user/release-keys\npost-security-patch-level=2026-10-01\n';
  return { buffer: zip([{ name: 'META-INF/com/android/metadata', data: metadata, method: 8 }, { name: 'payload.bin', data: result.buffer }]), expected: result.expected };
}
function createFixtures(directory) {
  fs.mkdirSync(directory, { recursive: true });
  const standard = ota();
  const selfContained = ota({ names: ['boot'], blocks: 4, minorVersion: 9 });
  const large = ota({ names: ['system'], blocks: 64, minorVersion: 9 });
  const bad = ota({ names: ['boot'], badHash: true });
  const delta = ota({ names: ['boot'], delta: true });
  const rawExpected = { 'boot.img': Buffer.alloc(8192, 31), 'vendor.img': Buffer.alloc(16384, 63) };
  const raw = zip([{ name: 'images/boot.img', data: rawExpected['boot.img'] }, { name: 'images/vendor.img', data: rawExpected['vendor.img'], method: 8 }]);
  const nested = zip([{ name: 'Software/dload/update_sd_base.zip', data: raw, method: 8 }]);
  const fixtures = { standard, selfContained, large, bad, delta, raw: { buffer: raw, expected: rawExpected }, nested: { buffer: nested, expected: rawExpected } };
  for (const [name, value] of Object.entries(fixtures)) { value.path = path.join(directory, `${name}-fixture.zip`); fs.writeFileSync(value.path, value.buffer); }
  return fixtures;
}
module.exports = { payload, ota, zip, createFixtures, sha, crc32 };
