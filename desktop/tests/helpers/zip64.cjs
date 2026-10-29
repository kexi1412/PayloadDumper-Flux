'use strict';
const assert = require('node:assert/strict');
// A real ZIP64 payload entry, with both classic sentinels and ZIP64 EOCD/locator.
// Optional virtualSize places the directory above 4 GiB without allocating that gap.
function zip64(data, crc, virtualSize = 0) {
  const name = Buffer.from('payload.bin');
  const localExtra = Buffer.alloc(20); localExtra.writeUInt16LE(1); localExtra.writeUInt16LE(16, 2);
  localExtra.writeBigUInt64LE(BigInt(data.length), 4); localExtra.writeBigUInt64LE(BigInt(data.length), 12);
  const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50); local.writeUInt16LE(45, 4); local.writeUInt16LE(0x800, 6);
  local.writeUInt32LE(crc, 14); local.writeUInt32LE(0xffffffff, 18); local.writeUInt32LE(0xffffffff, 22);
  local.writeUInt16LE(name.length, 26); local.writeUInt16LE(localExtra.length, 28);
  const prefix = Buffer.concat([local, name, localExtra, data]);
  const extra = Buffer.alloc(28); extra.writeUInt16LE(1); extra.writeUInt16LE(24, 2);
  extra.writeBigUInt64LE(BigInt(data.length), 4); extra.writeBigUInt64LE(BigInt(data.length), 12); extra.writeBigUInt64LE(0n, 20);
  const entry = Buffer.alloc(46); entry.writeUInt32LE(0x02014b50); entry.writeUInt16LE(45, 4); entry.writeUInt16LE(45, 6); entry.writeUInt16LE(0x800, 8);
  entry.writeUInt32LE(crc, 16); entry.writeUInt32LE(0xffffffff, 20); entry.writeUInt32LE(0xffffffff, 24);
  entry.writeUInt16LE(name.length, 28); entry.writeUInt16LE(extra.length, 30); entry.writeUInt32LE(0xffffffff, 42);
  const cd = Buffer.concat([entry, name, extra]);
  const tailLength = cd.length + 56 + 20 + 22;
  const cdOffset = virtualSize ? virtualSize - tailLength : prefix.length;
  assert(cdOffset >= prefix.length);
  const end64 = Buffer.alloc(56); end64.writeUInt32LE(0x06064b50); end64.writeBigUInt64LE(44n, 4);
  end64.writeUInt16LE(45, 12); end64.writeUInt16LE(45, 14); end64.writeBigUInt64LE(1n, 24); end64.writeBigUInt64LE(1n, 32);
  end64.writeBigUInt64LE(BigInt(cd.length), 40); end64.writeBigUInt64LE(BigInt(cdOffset), 48);
  const locator = Buffer.alloc(20); locator.writeUInt32LE(0x07064b50); locator.writeBigUInt64LE(BigInt(cdOffset + cd.length), 8); locator.writeUInt32LE(1, 16);
  const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(0xffff, 8); end.writeUInt16LE(0xffff, 10);
  end.writeUInt32LE(0xffffffff, 12); end.writeUInt32LE(0xffffffff, 16);
  const tail = Buffer.concat([cd, end64, locator, end]);
  const size = cdOffset + tail.length;
  function read(start, length) {
    assert(Number.isSafeInteger(start) && Number.isSafeInteger(length) && start >= 0 && length >= 0 && length <= 256 * 1024 && start + length <= size);
    const result = Buffer.alloc(length);
    for (const [position, bytes] of [[0, prefix], [cdOffset, tail]]) {
      const from = Math.max(start, position), to = Math.min(start + length, position + bytes.length);
      if (to > from) bytes.copy(result, from - start, from - position, to - position);
    }
    return result;
  }
  return { size, prefix, tail, cdOffset, read, buffer: virtualSize ? null : Buffer.concat([prefix, tail]) };
}
module.exports = { zip64 };
