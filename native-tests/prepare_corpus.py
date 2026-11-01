#!/usr/bin/env python3
"""Copies test-corpus/* into OUT and converts plist/tmLanguage fixtures to JSON (the C++ engine only reads JSON)."""
import json, os, plistlib, shutil, sys

def convert(src, dst):
    with open(src, 'rb') as f:
        data = plistlib.load(f)
    with open(dst, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)

def main(corpus, out):
    if os.path.isdir(out):
        shutil.rmtree(out)
    n = 0
    for suite in sorted(os.listdir(corpus)):
        sdir = os.path.join(corpus, suite)
        if not os.path.isfile(os.path.join(sdir, 'tests.json')):
            continue   # themes/ and samples/ are other suites
        odir = os.path.join(out, suite)
        os.makedirs(os.path.join(odir, 'fixtures'))
        for name in os.listdir(sdir):
            p = os.path.join(sdir, name)
            if os.path.isfile(p) and name.endswith('.json'):
                text = open(p, encoding='utf-8').read()
                # tests reference .plist/.tmLanguage fixtures: point them at the converted .json files
                for ext in ('.plist', '.tmLanguage'):
                    text = text.replace(ext + '"', ext + '.json"')
                open(os.path.join(odir, name), 'w', encoding='utf-8').write(text)
        fx = os.path.join(sdir, 'fixtures')
        for name in os.listdir(fx):
            src = os.path.join(fx, name)
            base, ext = os.path.splitext(name)
            if ext in ('.plist', '.tmLanguage'):
                dst = os.path.join(odir, 'fixtures', name + '.json')   # keep the old extension: Jade.json and Jade.tmLanguage differ
                assert not os.path.exists(dst), dst
                convert(src, dst); n += 1
            elif ext == '.json':
                shutil.copy(src, os.path.join(odir, 'fixtures', name))
    print('prepared corpus in', out, '(converted %d plist fixtures)' % n)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
