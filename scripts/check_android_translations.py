#!/usr/bin/env python3
"""Check the four offline UI languages without translation/lint exemptions."""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / 'android/app/src/main/res'
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?(?:[tT][A-Za-z]|[a-su-zA-SU-Z])')


def slots(text):
    return Counter(FORMAT.findall(text.replace('%%', '').replace('%n', '')))


def resources(directory):
    values, errors = {}, []
    for path in sorted((RES / directory).glob('*.xml')):
        for item in ET.parse(path).getroot():
            if item.tag not in {'string', 'plurals', 'string-array'} or item.get('translatable') == 'false':
                continue
            key = (item.tag, item.get('name'))
            if key in values:
                errors.append(f'{directory}: duplicate {key}')
            if item.tag == 'string':
                content = {'text': ''.join(item.itertext())}
            else:
                content = {child.get('quantity', str(index)): ''.join(child.itertext())
                           for index, child in enumerate(item)}
            values[key] = content
    return values, errors


def main():
    base, errors = resources('values')
    print(f'English base: {len(base)} translatable resources')
    for directory in ('values-zh', 'values-ja', 'values-ko'):
        translated, local_errors = resources(directory)
        local_errors += [f'{directory}: missing {key}' for key in sorted(base.keys() - translated.keys())]
        local_errors += [f'{directory}: unexpected {key}' for key in sorted(translated.keys() - base.keys())]
        for key in base.keys() & translated.keys():
            original, actual = base[key], translated[key]
            if key[0] == 'plurals':
                if 'other' not in actual:
                    local_errors.append(f'{directory}: {key} requires other quantity')
                pairs = [(quantity, original.get(quantity, original.get('other', '')), text)
                         for quantity, text in actual.items()]
            else:
                if actual.keys() != original.keys():
                    local_errors.append(f'{directory}: {key} item count differs')
                pairs = [(name, original.get(name, ''), text) for name, text in actual.items()]
            for name, expected, text in pairs:
                if slots(expected) != slots(text):
                    local_errors.append(f'{directory}: {key}/{name} format arguments differ')
        errors.extend(local_errors)
        print(f'{directory}: {len(local_errors)} missing/extra/duplicate/format errors')
    for error in errors:
        print(error, file=sys.stderr)
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
