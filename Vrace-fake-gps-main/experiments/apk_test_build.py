"""Create a local, narrowly patched test copy of the inspected vRace 2.5.5 APK set.

Uses Python's standard library. Does not install apps or contact a backend.
DEX instruction sizes and every existing code offset remain unchanged.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib

INPUT_HASHES = {
    'base.apk': 'f2e46c14d48ddf93ebafa2c10c0638939a80d4cc7aca03bbd889be2955a290a0',
    'split_config.vi.apk': '89c9b825c9da3eb4a8265d89052086d66059482d8fb6cb6575230abdb9728976',
    'split_config.xxhdpi.apk': 'd68eac51a5382d97dc5f38c95ab903779c038e90e63daa7020140f92f82b538a',
}
TARGETS = {
    ('Lfpt/vne/vrace/vrace_connect/TrackActivity;', 'lambda$checkFirstLocation$61'),
    ('Lfpt/vne/vrace/vrace_connect/service/GpsTrackingService$2;', 'onLocationResult'),
}
TEST_LABEL = 'vRace · Thử nghiệm'
ANDROID_NAMESPACE = 'http://schemas.android.com/apk/res/android'
MAPS_METADATA_NAME = 'com.google.android.geo.API_KEY'
RENDER_TARGET = ('Lfpt/vne/vrace/vrace_connect/TrackActivity;', 'lambda$updateTrack$44')
RENDER_CODE_SHA256 = 'fb446eef27d279a2e44a65c5607dba96306876be0efbf0beac6e2fec3c33b6c8'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def u16(data, offset):
    return struct.unpack_from('<H', data, offset)[0]


def u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            require(value <= 0xffffffff, 'Oversized ULEB128')
            return value, offset
    raise ValueError('Invalid ULEB128')


def instruction_width(words, index):
    word, opcode = words[index], words[index] & 255
    if opcode == 0:
        if word == 0:
            return 1
        if word == 0x0100:
            return 4 + 2 * words[index + 1]
        if word == 0x0200:
            return 2 + 4 * words[index + 1]
        if word == 0x0300:
            count = words[index + 2] | (words[index + 3] << 16)
            return 4 + (words[index + 1] * count + 1) // 2
        raise ValueError(f'Unknown instruction payload {word:#x}')
    if opcode in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c,
                  0x1f, 0x20, 0x22, 0x23, 0x29):
        return 2
    if opcode in (0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26,
                  0x2a, 0x2b, 0x2c) or 0x6e <= opcode <= 0x72 or 0x74 <= opcode <= 0x78:
        return 3
    if opcode == 0x18:
        return 5
    if 0x2d <= opcode <= 0x3d or 0x44 <= opcode <= 0x6d or 0x90 <= opcode <= 0xaf or 0xd0 <= opcode <= 0xe2:
        return 2
    if 0x01 <= opcode <= 0x12 or opcode in (0x1d, 0x1e, 0x21, 0x27, 0x28) or 0x7b <= opcode <= 0x8f or 0xb0 <= opcode <= 0xcf:
        return 1
    raise ValueError(f'Unsupported DEX 037 opcode {opcode:#x}')


def verify_dex(data):
    require(data[:8] == b'dex\n037\0', 'Only the inspected DEX 037 format is supported')
    require(u32(data, 32) == len(data) and u32(data, 36) == 112, 'Invalid DEX header')
    require(u32(data, 40) == 0x12345678, 'Unsupported DEX endianness')
    require(data[12:32] == hashlib.sha1(data[32:]).digest(), 'DEX SHA-1 mismatch')
    require(u32(data, 8) == zlib.adler32(data[12:]) & 0xffffffff, 'DEX Adler-32 mismatch')


def dex_methods(data, targets):
    """Read method references and code items for explicitly selected methods."""
    verify_dex(data)
    strings = []
    for i in range(u32(data, 56)):
        start = u32(data, u32(data, 60) + i * 4)
        _, start = uleb(data, start)
        # Target descriptors/names are ASCII; unrelated MUTF-8 strings are not rewritten.
        strings.append(data[start:data.index(b'\0', start)].decode('utf-8', errors='replace'))
    types = [strings[u32(data, u32(data, 68) + i * 4)] for i in range(u32(data, 64))]
    methods = []
    for i in range(u32(data, 88)):
        cls, proto, name = struct.unpack_from('<HHI', data, u32(data, 92) + i * 8)
        proto_offset = u32(data, 76) + proto * 12
        parameters = u32(data, proto_offset + 8)
        arguments = tuple(types[u16(data, parameters + 4 + j * 2)] for j in range(u32(data, parameters))) if parameters else ()
        methods.append((types[cls], strings[name], types[u32(data, proto_offset + 4)], arguments))
    code_items = []
    for i in range(u32(data, 96)):
        class_offset = u32(data, 100) + i * 32
        descriptor = types[u32(data, class_offset)]
        if descriptor not in {item[0] for item in targets}:
            continue
        offset = u32(data, class_offset + 24)
        require(offset != 0, 'Missing target class data')
        counts = []
        for _ in range(4):
            value, offset = uleb(data, offset)
            counts.append(value)
        for _ in range(counts[0] + counts[1]):
            _, offset = uleb(data, offset)
            _, offset = uleb(data, offset)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                diff, offset = uleb(data, offset)
                method_index += diff
                _, offset = uleb(data, offset)
                code_offset, offset = uleb(data, offset)
                method = methods[method_index]
                if (descriptor, method[1]) not in targets or not code_offset:
                    continue
                size = u32(data, code_offset + 12)
                start = code_offset + 16
                words = struct.unpack_from(f'<{size}H', data, start)
                code_items.append((method, code_offset, words))
    return strings, methods, code_items


def dex_calls(data):
    """Find calls at decoded instruction boundaries, only in the two known methods."""
    _, methods, code_items = dex_methods(data, TARGETS)
    mock_ids = {i: m[1] for i, m in enumerate(methods)
                if m[0] == 'Landroid/location/Location;' and m[1] in ('isMock', 'isFromMockProvider')
                and m[2] == 'Z' and not m[3]}
    # R8 outlines API 31's isMock() into this synthetic helper in the pinned APK.
    mock_ids.update({i: 'isMock' for i, m in enumerate(methods)
                     if m == ('Lfpt/vne/vrace/vrace_connect/i;', 'a', 'Z', ('Landroid/location/Location;',))})
    calls = []
    for method, code_offset, words in code_items:
        descriptor, size = method[0], len(words)
        start = code_offset + 16
        index = 0
        while index < size:
            width = instruction_width(words, index)
            require(index + width <= size, 'Truncated DEX instruction')
            if words[index] & 255 in (0x6e, 0x74, 0x71, 0x77) and words[index + 1] in mock_ids:
                require(index + 3 < size and words[index + 3] & 255 == 0x0a,
                        'Expected immediate boolean move-result')
                register = words[index + 3] >> 8
                require(register < u16(data, code_offset), 'Invalid result register')
                calls.append({'class': descriptor, 'method': method[1],
                              'api': mock_ids[words[index + 1]], 'offset': start + index * 2,
                              'register': register})
            index += width
    return calls


def patch_bounded_map(data):
    """Bound live-map overlays without touching recorded coordinates or session data.

    This pinned method has no other overlays. Replace its constant color parsing
    with map.clear() + the identical ARGB literal, in the same six code units.
    Clearing display overlays before addPolyline prevents one retained full-route
    overlay per callback. Code size, branches and exception tables are unchanged.
    """
    _, methods, code_items = dex_methods(data, {RENDER_TARGET})
    if not code_items:
        return data, []
    require(len(code_items) == 1, 'Expected exactly one live-map render method')
    method, code_offset, words = code_items[0]
    start = code_offset + 16
    require(u16(data, code_offset) == 10 and u16(data, code_offset + 6) == 0 and len(words) == 179,
            'Unsupported render code layout')
    require(hashlib.sha256(data[start:start + len(words) * 2]).hexdigest() == RENDER_CODE_SHA256,
            'Unexpected live-map render bytecode')
    clear = ('Lcom/google/android/gms/maps/GoogleMap;', 'clear', 'V', ())
    require(methods.count(clear) == 1, 'Expected one GoogleMap.clear reference')
    # v0 is the map, v9 the existing PolylineOptions, v1 the color argument.
    expected = (0x011a, 0x01c6, 0x1071, 0x0159, 0x0001, 0x010a)
    require(words[151:157] == expected, 'Unexpected render color sequence')
    replacement = (0x106e, methods.index(clear), 0, 0x0114, 0x9933, 0xff00)
    patched = bytearray(data)
    offset = start + 151 * 2
    patched[offset:offset + 12] = struct.pack('<6H', *replacement)
    patched[12:32] = hashlib.sha1(patched[32:]).digest()
    struct.pack_into('<I', patched, 8, zlib.adler32(patched[12:]) & 0xffffffff)
    verify_dex(patched)
    event = {'class': method[0], 'method': method[1], 'api': 'bounded_map_overlays',
             'offset': offset, 'size': 12}
    return bytes(patched), [event]


def patch_dex(data, calls):
    patched = bytearray(data)
    for call in calls:
        # invoke (3 units) + move-result (1 unit) -> const/16 false + two nops.
        # Same 8 bytes: branch offsets, try ranges, debug positions stay valid.
        replacement = struct.pack('<4H', 0x13 | call['register'] << 8, 0, 0, 0)
        patched[call['offset']:call['offset'] + 8] = replacement
    if calls:
        patched[12:32] = hashlib.sha1(patched[32:]).digest()
        struct.pack_into('<I', patched, 8, zlib.adler32(patched[12:]) & 0xffffffff)
    verify_dex(patched)
    require(not dex_calls(bytes(patched)), 'Mock calls remain in a patched target method')
    return bytes(patched)


def xml_chunks(data):
    require(u16(data, 0) == 3 and u16(data, 2) == 8 and u32(data, 4) == len(data), 'Invalid binary XML')
    offset = 8
    while offset < len(data):
        size = u32(data, offset + 4)
        require(size >= 8 and size % 4 == 0 and offset + size <= len(data), 'Invalid XML chunk')
        yield offset, u16(data, offset), data[offset:offset + size]
        offset += size


def pool_strings(pool):
    def read_length(data, pos, utf8):
        value = data[pos] if utf8 else u16(data, pos)
        step, mask = (1, 0x80) if utf8 else (2, 0x8000)
        pos += step
        if value & mask:
            next_value = data[pos] if utf8 else u16(data, pos)
            value = ((value & (mask - 1)) << (8 if utf8 else 16)) | next_value
            pos += step
        return value, pos
    utf8 = bool(u32(pool, 16) & 0x100)
    strings = []
    for i in range(u32(pool, 8)):
        pos = u32(pool, 20) + u32(pool, u16(pool, 2) + i * 4)
        length, pos = read_length(pool, pos, utf8)
        if utf8:
            length, pos = read_length(pool, pos, True)
        strings.append(pool[pos:pos + length * (1 if utf8 else 2)].decode('utf-8' if utf8 else 'utf-16-le'))
    return strings


def append_pool_string(pool, text):
    require(u16(pool, 2) == 28 and u32(pool, 12) == 0 and u32(pool, 24) == 0, 'Unsupported styled XML pool')
    count, flags, start = u32(pool, 8), u32(pool, 16), u32(pool, 20)
    utf16_length = len(text.encode('utf-16-le')) // 2
    if flags & 0x100:
        encoded = text.encode('utf-8')
        require(len(encoded) < 128 and utf16_length < 128, 'Test label is too long')
        encoded = bytes((utf16_length, len(encoded))) + encoded + b'\0'
    else:
        encoded = struct.pack('<H', utf16_length) + text.encode('utf-16-le') + b'\0\0'
    old_strings = pool[start:]
    new_strings = old_strings + encoded
    new_strings += b'\0' * (-len(new_strings) % 4)
    indices = pool[28:start] + struct.pack('<I', len(old_strings))
    header = bytearray(pool[:28])
    struct.pack_into('<I', header, 4, 28 + len(indices) + len(new_strings))
    struct.pack_into('<I', header, 8, count + 1)
    struct.pack_into('<I', header, 16, flags & ~1)  # appended pool is no longer sorted
    struct.pack_into('<I', header, 20, start + 4)
    return bytes(header) + indices + new_strings, count


def manifest_info(data):
    strings = None
    info = {}
    for _, kind, chunk in xml_chunks(data):
        if kind == 1:
            strings = pool_strings(chunk)
        elif kind == 0x102:
            require(strings is not None, 'XML has no string pool')
            element = strings[u32(chunk, 20)]
            start, size, count = struct.unpack_from('<HHH', chunk, 24)
            for i in range(count):
                attr = 16 + start + i * size
                name = strings[u32(chunk, attr + 4)]
                value_type, value = chunk[attr + 15], u32(chunk, attr + 16)
                if element == 'manifest' and name in ('package', 'versionCode', 'versionName', 'split'):
                    info[name] = strings[value] if value_type == 3 else value
                if element == 'application' and name == 'label':
                    info['label'] = strings[value] if value_type == 3 else f'@{value:#x}'
    return info


def validate_maps_api_key(key):
    # Validate without including credential contents in errors or reports.
    require(isinstance(key, str) and re.fullmatch(r'AIza[0-9A-Za-z_-]{35}', key) is not None,
            'Expected one Google Maps API key in the local key file')
    return key


def read_maps_api_key(path):
    file = Path(path)
    require(file.stat().st_size <= 4096, 'Maps key file is too large')
    return validate_maps_api_key(file.read_text(encoding='utf-8-sig').strip())


def label_manifest(data, maps_api_key=None):
    if maps_api_key is not None:
        validate_maps_api_key(maps_api_key)
    strings, label_index, key_index, updated, maps_updated, chunks = None, None, None, 0, 0, []
    stack = []
    for _, kind, original in xml_chunks(data):
        chunk = bytearray(original)
        if kind == 1:
            require(strings is None, 'Multiple XML string pools')
            strings = pool_strings(original)
            chunk, label_index = append_pool_string(original, TEST_LABEL)
            if maps_api_key is not None:
                chunk, key_index = append_pool_string(chunk, maps_api_key)
        elif kind == 0x102:
            require(strings is not None, 'XML has no string pool')
            element = strings[u32(chunk, 20)]
            start, size, count = struct.unpack_from('<HHH', chunk, 24)
            attributes = {}
            for i in range(count):
                attr = 16 + start + i * size
                namespace = u32(chunk, attr)
                if namespace != 0xffffffff and strings[namespace] == ANDROID_NAMESPACE:
                    attributes[strings[u32(chunk, attr + 4)]] = attr
            if element == 'application' and 'label' in attributes:
                attr = attributes['label']
                struct.pack_into('<I', chunk, attr + 8, label_index)
                struct.pack_into('<HBBI', chunk, attr + 12, 8, 0, 3, label_index)
                updated += 1
            if maps_api_key is not None and element == 'meta-data' and stack and stack[-1] == 'application':
                name = attributes.get('name')
                if name is not None and chunk[name + 15] == 3 and strings[u32(chunk, name + 16)] == MAPS_METADATA_NAME:
                    require('value' in attributes, 'Maps metadata has no android:value')
                    attr = attributes['value']
                    struct.pack_into('<I', chunk, attr + 8, key_index)
                    struct.pack_into('<HBBI', chunk, attr + 12, 8, 0, 3, key_index)
                    maps_updated += 1
            stack.append(element)
        elif kind == 0x103:
            require(stack and strings[u32(chunk, 20)] == stack[-1], 'Unbalanced XML elements')
            stack.pop()
        chunks.append(bytes(chunk))
    require(updated == 1, 'Expected exactly one application label')
    require(not stack, 'Unbalanced XML elements')
    if maps_api_key is not None:
        require(maps_updated == 1, 'Expected exactly one application Google Maps metadata entry')
    result = struct.pack('<HHI', 3, 8, 8 + sum(map(len, chunks))) + b''.join(chunks)
    require(manifest_info(result)['label'] == TEST_LABEL, 'Failed to identify test build')
    return result


def is_signature_entry(name):
    name = name.upper()
    return name.startswith('META-INF/') and (name == 'META-INF/MANIFEST.MF' or name.endswith(('.SF', '.RSA', '.DSA', '.EC')))


def write_archive(source, target, replacements):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w', allowZip64=False) as output:
        require(len(original.namelist()) == len(set(original.namelist())), 'Duplicate ZIP entries')
        for entry in original.infolist():
            if is_signature_entry(entry.filename):
                continue
            info = copy.copy(entry)
            payload = replacements.get(entry.filename, original.read(entry.filename))
            if info.compress_type == zipfile.ZIP_STORED:
                alignment = 4096 if info.filename.endswith('.so') else 4
                data_offset = output.fp.tell() + 30 + len(info.filename.encode('utf-8')) + len(info.extra)
                if data_offset % alignment:
                    padding = (-data_offset - 4) % alignment
                    info.extra += struct.pack('<HH', 0xffff, padding) + bytes(padding)
            output.writestr(info, payload)
    verify_archive(source, target, replacements)


def verify_archive(source, target, replacements):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target) as output:
        expected = {name for name in original.namelist() if not is_signature_entry(name)}
        require(set(output.namelist()) == expected, 'Archive entries changed unexpectedly')
        require(output.testzip() is None, 'ZIP CRC check failed')
        for entry in output.infolist():
            require(output.read(entry.filename) == replacements.get(entry.filename, original.read(entry.filename)),
                    f'Unexpected content change: {entry.filename}')
        with open(target, 'rb') as file:
            for entry in output.infolist():
                if entry.compress_type != zipfile.ZIP_STORED:
                    continue
                file.seek(entry.header_offset + 26)
                name_length, extra_length = struct.unpack('<HH', file.read(4))
                alignment = 4096 if entry.filename.endswith('.so') else 4
                require((entry.header_offset + 30 + name_length + extra_length) % alignment == 0,
                        f'Unaligned stored entry: {entry.filename}')


def prepare(input_dir, output_dir, inspect_only=False, verify_only=False, maps_api_key=None, bounded_map=False):
    if maps_api_key is not None:
        validate_maps_api_key(maps_api_key)
    input_dir, output_dir = Path(input_dir).resolve(), Path(output_dir).resolve()
    require(input_dir != output_dir and input_dir not in output_dir.parents, 'Keep input APK directory separate from output')
    for name, expected in INPUT_HASHES.items():
        require(hashlib.sha256((input_dir / name).read_bytes()).hexdigest() == expected,
                f'Unsupported input APK: {name}; originals must be the inspected 2.5.5 set')
    replacements, events = {}, []
    with zipfile.ZipFile(input_dir / 'base.apk') as archive:
        metadata = manifest_info(archive.read('AndroidManifest.xml'))
        require(metadata['package'] == 'fpt.vne.vrace' and metadata['versionCode'] == 225, 'Wrong application')
        for name in archive.namelist():
            if name.endswith('.dex'):
                data = archive.read(name)
                calls = dex_calls(data)
                events.extend(dict(call, dex=name) for call in calls)
                modified = patch_dex(data, calls) if calls else data
                if bounded_map:
                    modified, render_events = patch_bounded_map(modified)
                    events.extend(dict(event, dex=name) for event in render_events)
                if modified != data and not inspect_only:
                    replacements[name] = modified
        if not inspect_only:
            replacements['AndroidManifest.xml'] = label_manifest(archive.read('AndroidManifest.xml'), maps_api_key)
    expected_counts = Counter((cls, method, api) for cls, method in TARGETS for api in ('isMock', 'isFromMockProvider'))
    if bounded_map:
        expected_counts[(RENDER_TARGET[0], RENDER_TARGET[1], 'bounded_map_overlays')] = 1
    require(Counter((event['class'], event['method'], event['api']) for event in events) == expected_counts,
            f'Unexpected mock-check locations or count: {events}')
    report = {'version': 1, 'input': INPUT_HASHES, 'package': metadata['package'], 'versionName': metadata['versionName'],
              'label': TEST_LABEL, 'patches': events, 'modified_entries': sorted(replacements),
              'device_tested': False, 'backend_tested': False, 'installed': False,
              'maps_key_overridden': maps_api_key is not None, 'maps_authorization_verified': False,
              'bounded_map_overlays': bounded_map, 'phone_performance_verified': False}
    if not inspect_only:
        if not verify_only:
            output_dir.mkdir(parents=True, exist_ok=True)
        for name in INPUT_HASHES:
            operation = verify_archive if verify_only else write_archive
            operation(input_dir / name, output_dir / name, replacements if name == 'base.apk' else {})
        for name, expected in INPUT_HASHES.items():
            require(hashlib.sha256((input_dir / name).read_bytes()).hexdigest() == expected, 'Original APK changed')
        if not verify_only:
            (output_dir / 'patch-report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
        report['content_verified'] = True
        if verify_only:
            build_report = output_dir / 'build-report.json'
            if build_report.exists():
                signed_report = json.loads(build_report.read_text(encoding='utf-8'))
                signed_report['content_verified'] = True
                signed_report['original_inputs_unchanged'] = True
                build_report.write_text(json.dumps(signed_report, indent=2, ensure_ascii=False), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', default='../platform-tools/vrace-apks')
    parser.add_argument('--output-dir', default='artifacts/vrace-test/unsigned')
    parser.add_argument('--inspect', action='store_true')
    parser.add_argument('--bounded-map', action='store_true', help='Clear previous live-map overlays before redrawing the route')
    parser.add_argument('--maps-api-key-file', help='Local file containing your own authorized Maps SDK key; key is never printed')
    parser.add_argument('--verify-signed', metavar='DIRECTORY', help='Verify signed output payloads against exact expected changes')
    args = parser.parse_args()
    key = read_maps_api_key(args.maps_api_key_file) if args.maps_api_key_file else None
    print(json.dumps(prepare(args.input_dir, args.verify_signed or args.output_dir, args.inspect, bool(args.verify_signed), key, args.bounded_map), indent=2, ensure_ascii=True))
