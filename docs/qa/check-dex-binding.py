#!/usr/bin/env python3
"""Read DEX class declarations (not references) from production/test APKs."""
import hashlib, json, pathlib, struct, sys, zipfile

def declared_classes(path):
    result = set()
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not (name.startswith('classes') and name.endswith('.dex')):
                continue
            data = archive.read(name)
            if not data.startswith(b'dex\n'):
                raise ValueError(f'Unexpected DEX header: {name}')
            u32 = lambda offset: struct.unpack_from('<I', data, offset)[0]
            strings = u32(0x3c)
            types = u32(0x44)
            count, defs = u32(0x60), u32(0x64)
            for index in range(count):
                type_index = u32(defs + index * 32)
                string_index = u32(types + type_index * 4)
                offset = u32(strings + string_index * 4)
                # Skip the unsigned LEB128 UTF-16 length. Class descriptors are ASCII.
                for _ in range(5):
                    value = data[offset]
                    offset += 1
                    if not value & 0x80:
                        break
                else:
                    raise ValueError('Malformed descriptor length')
                end = data.index(b'\0', offset)
                result.add(data[offset:end].decode('ascii'))
    return result

production, tests, output = map(pathlib.Path, sys.argv[1:4])
prod_defs, test_defs = declared_classes(production), declared_classes(tests)
prefix = 'Lio/github/ktclat/c001apk/nativeapp/'
prod_app = sorted(c for c in prod_defs if c.startswith(prefix))
test_app = sorted(c for c in test_defs if c.startswith(prefix))
overlap = sorted(set(prod_app) & set(test_app))
required = [prefix + 'MainActivity;', prefix + 'ui/NativeAppKt;', prefix + 'account/GraphicCaptchaPromptKt;']
assert all(c in prod_defs for c in required), 'Expected production classes are absent'
assert not overlap, f'Test APK shadows production native classes: {overlap}'
assert not any('EmulatorEvidence' in c or 'EmulatorGraphicRetryTest' in c for c in prod_app), 'Harness declarations in production APK'
receipt = {
    'production_apk': str(production), 'test_apk': str(tests),
    'production_sha256': hashlib.sha256(production.read_bytes()).hexdigest(),
    'test_sha256': hashlib.sha256(tests.read_bytes()).hexdigest(),
    'production_native_class_count': len(prod_app), 'test_native_namespace_class_count': len(test_app),
    'production_native_class_shadowing_in_test_apk': overlap,
    'required_production_class_declarations': required,
    'production_native_classes': prod_app, 'test_native_namespace_classes': test_app,
    'scope': 'DEX class definitions, not mere string references. No shared production-native class declarations in test APK.'
}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({k:v for k,v in receipt.items() if not k.endswith('_classes')}, indent=2))
