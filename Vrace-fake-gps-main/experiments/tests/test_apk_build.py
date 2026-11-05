import hashlib
from functools import lru_cache
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from experiments.apk_test_build import (INPUT_HASHES, TEST_LABEL, dex_calls, instruction_width,
                                        label_manifest, manifest_info, patch_dex, prepare, uleb,
                                        verify_archive, verify_dex, write_archive, pool_strings,
                                        xml_chunks, u32, read_maps_api_key, MAPS_METADATA_NAME,
                                        dex_methods, patch_bounded_map, RENDER_TARGET)

ROOT = Path(__file__).resolve().parents[2]
INPUT = ROOT.parent / 'platform-tools' / 'vrace-apks'
LOCAL_INPUTS = all((INPUT / name).is_file() for name in INPUT_HASHES)
TEST_MAPS_KEY = 'AIza' + '0' * 35  # Deliberately fake; never sent to Google.


def maps_values(data):
    """Independently decode raw/typed metadata values to catch incorrect string references."""
    values = []
    for _, kind, chunk in xml_chunks(data):
        if kind == 1:
            strings = pool_strings(chunk)
        elif kind == 0x102 and strings[u32(chunk, 20)] == 'meta-data':
            start, size, count = struct.unpack_from('<HHH', chunk, 24)
            attributes = {strings[u32(chunk, 16 + start + i * size + 4)]: 16 + start + i * size
                          for i in range(count)}
            name = attributes.get('name')
            if name is not None and strings[u32(chunk, name + 16)] == MAPS_METADATA_NAME:
                value = attributes['value']
                values.append((strings[u32(chunk, value + 8)], strings[u32(chunk, value + 16)], chunk[value + 15]))
    return values


def xml_fixture(utf8=True, metadata_count=1):
    strings = ['http://schemas.android.com/apk/res/android', 'application', 'label', 'Original',
               'meta-data', 'name', 'value', MAPS_METADATA_NAME, 'old-placeholder', 'other-metadata', 'unrelated']
    entries, offsets = b'', []
    for text in strings:
        offsets.append(len(entries))
        encoded = text.encode('utf-8' if utf8 else 'utf-16-le')
        entries += (bytes([len(text), len(encoded)]) + encoded + b'\0' if utf8 else
                    struct.pack('<H', len(text)) + encoded + b'\0\0')
    entries += b'\0' * (-len(entries) % 4)
    start = 28 + len(strings) * 4
    pool = struct.pack('<HH6I', 1, 28, start + len(entries), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack(f'<{len(offsets)}I', *offsets) + entries
    def begin(element, attrs):
        header = struct.pack('<HHIII', 0x102, 16, 36 + len(attrs) * 20, 1, 0xffffffff)
        header += struct.pack('<II6H', 0xffffffff, element, 20, 20, len(attrs), 0, 0, 0)
        return header + b''.join(struct.pack('<IIIHBBI', 0, name, value, 8, 0, 3, value) for name, value in attrs)
    def end(element):
        return struct.pack('<HH5I', 0x103, 16, 24, 1, 0xffffffff, 0xffffffff, element)
    chunks = pool + begin(1, [(2, 3)])
    for _ in range(metadata_count):
        chunks += begin(4, [(5, 7), (6, 8)]) + end(4)
    chunks += begin(4, [(5, 9), (6, 10)]) + end(4) + end(1)
    return struct.pack('<HHI', 3, 8, 8 + len(chunks)) + chunks


@lru_cache(maxsize=2)
def render_code(data):
    strings, methods, items = dex_methods(data, {RENDER_TARGET})
    return strings, methods, items[0][2]


def execute_render_tail(data, points, overlays, has_map=True):
    """Execute the actual pinned DEX render tail against a fake Maps API.

    This checks register use, branches, color and overlay lifetime, not Android
    renderer performance. Unknown instructions/calls fail instead of being skipped.
    """
    strings, methods, words = render_code(data)
    regs = {8: {'map': overlays if has_map else None, 'points': points}, 9: points}
    pos, result, calls = 131, None, []
    for _ in range(100):
        word = words[pos]
        opcode = word & 255
        dest = word >> 8
        width = instruction_width(words, pos)
        if opcode == 0x54:  # iget-object
            target, owner = dest & 15, dest >> 4
            field = {0x2745: 'map', 0x2722: 'points'}[words[pos + 1]]
            regs[target] = regs[owner][field]
        elif opcode in (0x38, 0x3d):  # if-eqz / if-lez
            value = regs[dest]
            branch = (value is None or value == 0) if opcode == 0x38 else value <= 0
            if branch:
                offset = struct.unpack('<h', struct.pack('<H', words[pos + 1]))[0]
                pos += offset
                continue
        elif opcode == 0x21:  # array-length
            regs[dest & 15] = len(regs[dest >> 4])
        elif opcode == 0x22:  # new-instance PolylineOptions
            regs[dest] = {}
        elif opcode == 0x1a:  # const-string
            regs[dest] = strings[words[pos + 1]]
        elif opcode == 0x14:  # const, signed ARGB int
            regs[dest] = struct.unpack('<i', struct.pack('<HH', *words[pos + 1:pos + 3]))[0]
        elif opcode == 0x15:  # const/high16, float width
            regs[dest] = struct.unpack('<f', struct.pack('<I', words[pos + 1] << 16))[0]
        elif opcode in (0x6e, 0x70, 0x71):
            method = methods[words[pos + 1]]
            count = word >> 12
            arg_word = words[pos + 2]
            register_ids = [(arg_word >> (4 * i)) & 15 for i in range(4)] + [(word >> 8) & 15]
            args = [regs[r] for r in register_ids[:count]]
            owner, name = method[:2]
            if owner == 'Lcom/google/android/gms/maps/model/PolylineOptions;':
                options = args[0]
                if name == 'add':
                    options['points'] = list(args[1])
                elif name in ('color', 'width'):
                    options[name] = args[1]
                elif name != '<init>':
                    raise AssertionError(method)
                result = options
            elif owner == 'Lcom/google/android/gms/maps/GoogleMap;':
                calls.append(name)
                if name == 'clear':
                    args[0].clear()
                    result = None
                elif name == 'addPolyline':
                    args[0].append(dict(args[1]))
                    result = args[1]
                else:
                    raise AssertionError(method)
            elif method[:2] == ('Landroid/graphics/Color;', 'parseColor'):
                result = int(args[0][1:], 16) | 0xff000000
                if result >= 0x80000000:
                    result -= 0x100000000
            elif method[:2] != ('Lfpt/vne/vrace/utils/LogUtils;', 'error'):
                raise AssertionError(method)
        elif opcode in (0x0a, 0x0c):
            regs[dest] = result
        elif opcode == 0x28:  # goto
            pos += struct.unpack('<b', bytes([dest]))[0]
            continue
        elif opcode == 0x0e:
            return calls
        else:
            raise AssertionError(f'Unsupported render instruction {opcode:#x}')
        pos += width
    raise AssertionError('Render tail did not return')


class ParserTests(unittest.TestCase):
    def test_maps_override_in_both_xml_encodings_preserves_other_elements(self):
        for utf8 in (True, False):
            original = xml_fixture(utf8)
            labeled = label_manifest(original)
            patched = label_manifest(original, TEST_MAPS_KEY)
            self.assertEqual(maps_values(patched), [(TEST_MAPS_KEY, TEST_MAPS_KEY, 3)])
            self.assertEqual(manifest_info(patched)['label'], TEST_LABEL)
            before, after = list(xml_chunks(labeled)), list(xml_chunks(patched))
            # Only the pool and the Maps metadata start element may differ from the labeled build.
            for i, (old, new) in enumerate(zip(before, after)):
                if i not in (0, 2):
                    self.assertEqual(old[2], new[2])
            old_strings, new_strings = pool_strings(before[0][2]), pool_strings(after[0][2])
            self.assertEqual(new_strings[:-1], old_strings)
            self.assertEqual(new_strings[-1], TEST_MAPS_KEY)
            self.assertEqual(maps_values(labeled), [('old-placeholder', 'old-placeholder', 3)])

    def test_missing_or_duplicate_maps_metadata_is_rejected(self):
        for count in (0, 2):
            with self.assertRaisesRegex(ValueError, 'exactly one application Google Maps'):
                label_manifest(xml_fixture(metadata_count=count), TEST_MAPS_KEY)

    def test_key_file_accepts_bom_and_rejects_invalid_keys_without_echoing_them(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'key.txt'
            file.write_text(TEST_MAPS_KEY + '\n', encoding='utf-8-sig')
            self.assertEqual(read_maps_api_key(file), TEST_MAPS_KEY)
            for invalid in ('', 'not-a-key-keep-private', TEST_MAPS_KEY + '\n' + TEST_MAPS_KEY):
                file.write_text(invalid, encoding='utf-8')
                with self.assertRaises(ValueError) as error:
                    read_maps_api_key(file)
                if invalid:
                    self.assertNotIn(invalid, str(error.exception))
            file.write_bytes(b'x' * 4097)
            with self.assertRaisesRegex(ValueError, 'too large'):
                read_maps_api_key(file)

    def test_rejects_malformed_leb_and_unsupported_instruction(self):
        with self.assertRaises(ValueError):
            uleb(bytes([0x80] * 5), 0)
        with self.assertRaises(ValueError):
            instruction_width([0x73], 0)

    def test_payload_is_skipped_as_data_not_interpreted_as_invocations(self):
        self.assertEqual(instruction_width([0x0100, 2, 0, 0, 0x6e, 0, 0x6e, 0], 0), 8)
        self.assertEqual(instruction_width([0x0300, 1, 5, 0, 0, 0, 0], 0), 7)

    def test_rejects_unrecognized_input_before_writing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            original, output = Path(directory) / 'input', Path(directory) / 'output'
            original.mkdir()
            for name in INPUT_HASHES:
                (original / name).write_bytes(b'wrong APK')
            with self.assertRaisesRegex(ValueError, 'Unsupported input'):
                prepare(original, output)
            self.assertFalse(output.exists())

    def test_stored_zip_entries_are_aligned_without_changing_payloads(self):
        with tempfile.TemporaryDirectory() as directory:
            original, output = Path(directory) / 'original.apk', Path(directory) / 'test.apk'
            with zipfile.ZipFile(original, 'w') as archive:
                archive.writestr('a.txt', b'a')
                archive.writestr('resources.arsc', b'fixture')
                archive.writestr('lib/arm64-v8a/test.so', b'not a real library')
                archive.writestr('META-INF/CERT.SF', b'old signature')
            write_archive(original, output, {})
            verify_archive(original, output, {})
            with zipfile.ZipFile(output) as archive:
                self.assertNotIn('META-INF/CERT.SF', archive.namelist())


@unittest.skipUnless(LOCAL_INPUTS, 'Original APKs are local analysis inputs, not public repository files')
class LocalApkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(INPUT / 'base.apk') as archive:
            cls.dex = archive.read('classes3.dex')
            cls.manifest = archive.read('AndroidManifest.xml')

    def test_exact_four_calls_and_no_unrelated_byte_changes(self):
        calls = dex_calls(self.dex)
        self.assertEqual(len(calls), 4)
        patched = patch_dex(self.dex, calls)
        self.assertEqual(len(patched), len(self.dex))
        allowed = set(range(8, 32))
        for call in calls:
            allowed.update(range(call['offset'], call['offset'] + 8))
            self.assertEqual(struct.unpack_from('<4H', patched, call['offset']),
                             (0x13 | call['register'] << 8, 0, 0, 0))
        self.assertTrue(all(i in allowed for i, (a, b) in enumerate(zip(self.dex, patched)) if a != b))
        self.assertEqual(dex_calls(patched), [])
        verify_dex(patched)

    def test_manifest_keeps_package_and_version_and_labels_test_build(self):
        before, after = manifest_info(self.manifest), manifest_info(label_manifest(self.manifest))
        self.assertEqual(after['label'], TEST_LABEL)
        for key in ('package', 'versionCode', 'versionName'):
            self.assertEqual(before[key], after[key])

    def test_original_inputs_match_pinned_hashes(self):
        for name, expected in INPUT_HASHES.items():
            self.assertEqual(hashlib.sha256((INPUT / name).read_bytes()).hexdigest(), expected)

    def test_maps_override_in_real_manifest_keeps_identity_and_other_metadata(self):
        labeled = label_manifest(self.manifest)
        patched = label_manifest(self.manifest, TEST_MAPS_KEY)
        self.assertEqual(manifest_info(labeled), manifest_info(patched))
        self.assertEqual(maps_values(patched), [(TEST_MAPS_KEY, TEST_MAPS_KEY, 3)])
        before, after = list(xml_chunks(labeled)), list(xml_chunks(patched))
        differences = [(old[1], new[1]) for old, new in zip(before, after) if old[2] != new[2]]
        self.assertEqual(differences, [(1, 1), (0x102, 0x102)])

    def test_bounded_map_changes_only_pinned_display_bytes(self):
        mock_calls = dex_calls(self.dex)
        original = patch_dex(self.dex, mock_calls)
        patched, events = patch_bounded_map(original)
        self.assertEqual(len(events), 1)
        self.assertEqual(len(patched), len(original))
        offset = events[0]['offset']
        allowed = set(range(8, 32)) | set(range(offset, offset + 12))
        self.assertTrue(all(i in allowed for i, (a, b) in enumerate(zip(original, patched)) if a != b))
        verify_dex(patched)
        self.assertEqual(dex_calls(patched), [])
        # Re-applying or accepting a different render body must fail closed.
        with self.assertRaisesRegex(ValueError, 'Unexpected live-map render bytecode'):
            patch_bounded_map(patched)

    def test_repeated_out_and_back_render_keeps_one_overlay_and_all_coordinates(self):
        patched, _ = patch_bounded_map(self.dex)
        a, b, near_a = (10.8784, 106.7972), (10.8804, 106.7992), (10.87841, 106.79721)
        points = [a, b, near_a, b]
        old_overlays, new_overlays = [], []
        for update in range(1000):
            # Repeated callbacks at an overlapping route, including a growing history.
            history = points * (update // 100 + 1)
            execute_render_tail(self.dex, history, old_overlays)
            self.assertEqual(execute_render_tail(patched, history, new_overlays), ['clear', 'addPolyline'])
            self.assertEqual(len(new_overlays), 1)
            self.assertEqual(new_overlays[-1], old_overlays[-1])
            self.assertEqual(new_overlays[-1]['points'], history)
            self.assertEqual(new_overlays[-1]['color'], -16737997)  # 0xff009933
            self.assertEqual(new_overlays[-1]['width'], 9.0)
        self.assertEqual(len(old_overlays), 1000)

    def test_missing_map_or_empty_points_does_not_clear_or_draw(self):
        patched, _ = patch_bounded_map(self.dex)
        for points, has_map in ((None, True), ([], True), ([(1, 2)], False)):
            sentinel = [{'previous': True}]
            self.assertEqual(execute_render_tail(patched, points, sentinel, has_map), [])
            self.assertEqual(sentinel, [{'previous': True}])

    def test_optional_build_and_signed_verification_expect_same_render_patch(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'unsigned'
            report = prepare(INPUT, output, bounded_map=True)
            self.assertTrue(report['bounded_map_overlays'])
            self.assertFalse(report['phone_performance_verified'])
            self.assertEqual(len(report['patches']), 5)
            self.assertEqual(report['modified_entries'], ['AndroidManifest.xml', 'classes3.dex'])
            prepare(INPUT, output, verify_only=True, bounded_map=True)
            with self.assertRaisesRegex(ValueError, 'Unexpected content change: classes3.dex'):
                prepare(INPUT, output, verify_only=True, bounded_map=False)


if __name__ == '__main__':
    unittest.main()
