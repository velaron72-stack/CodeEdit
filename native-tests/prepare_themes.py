#!/usr/bin/env python3
"""Converts vscode-textmate test-cases/themes into JSON-only data for run_themes.cpp.
usage: prepare_themes.py <test-cases/themes> <out>"""
import json, os, plistlib, shutil, sys

THEMES = [  # (name, file, include)
    ('abyss', 'Abyss.tmTheme', None), ('dark_vs', 'dark_vs.json', None), ('light_vs', 'light_vs.json', None),
    ('hc_black', 'hc_black.json', None), ('dark_plus', 'dark_plus.json', 'dark_vs.json'),
    ('light_plus', 'light_plus.json', 'light_vs.json'), ('kimbie_dark', 'Kimbie_dark.tmTheme', None),
    ('monokai', 'Monokai.tmTheme', None), ('monokai_dimmed', 'dimmed-monokai.tmTheme', None),
    ('quietlight', 'QuietLight.tmTheme', None), ('red', 'red.tmTheme', None),
    ('solarized_dark', 'Solarized-dark.tmTheme', None), ('solarized_light', 'Solarized-light.tmTheme', None),
    ('tomorrow_night_blue', 'Tomorrow-Night-Blue.tmTheme', None)]

def load(path):
    if path.endswith('.json'):
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    with open(path, 'rb') as f:
        return plistlib.load(f)

def main(src, out):
    if os.path.isdir(out):
        shutil.rmtree(out)
    for d in ('themes', 'grammars', 'tests'):
        os.makedirs(os.path.join(out, d))
    plan = {'themes': [], 'tests': []}
    for name, file, inc in THEMES:
        t = load(os.path.join(src, file))
        if inc:
            t['settings'] = load(os.path.join(src, inc))['settings'] + t['settings']
        t.pop('include', None)
        json.dump(t, open(os.path.join(out, 'themes', name + '.json'), 'w'))
        plan['themes'].append({'name': name, 'file': 'themes/%s.json' % name})
    grammars = json.load(open(os.path.join(src, 'grammars.json')))
    languages = json.load(open(os.path.join(src, 'languages.json')))
    manifest = {'format': 1, 'languages': [], 'themes': []}
    for i, g in enumerate(grammars):
        p = os.path.join(src, g['path'])
        data = load(p)
        fn = '%02d.json' % i
        json.dump(data, open(os.path.join(out, 'grammars', fn), 'w'))
        manifest['languages'].append({'id': g.get('language') or 'g%d' % i, 'name': g.get('language') or 'g%d' % i,
                                      'scope': g['scopeName'], 'file': fn})
    json.dump(manifest, open(os.path.join(out, 'manifest.json'), 'w'))
    def lang_by_ext(ext):
        for l in languages:
            if ext in (l.get('extensions') or []): return l['id']
    def lang_by_name(n):
        for l in languages:
            if n in (l.get('filenames') or []): return l['id']
    for fn in sorted(os.listdir(os.path.join(src, 'tests'))):
        if fn.endswith(('.result', '.result.patch', '.actual', '.diff.html')):
            continue
        lang = lang_by_ext(os.path.splitext(fn)[1]) or lang_by_name(fn)
        assert lang, fn
        g = next(g for g in grammars if g.get('language') == lang)
        shutil.copy(os.path.join(src, 'tests', fn), os.path.join(out, 'tests', fn))
        shutil.copy(os.path.join(src, 'tests', fn + '.result'), os.path.join(out, 'tests', fn + '.result'))
        plan['tests'].append({'file': fn, 'scope': g['scopeName']})
    shutil.copy(os.path.join(src, 'fixtures', 'test1.ts'), os.path.join(out, 'tests', 'perf1.ts'))
    shutil.copy(os.path.join(src, 'fixtures', 'test2.ts'), os.path.join(out, 'tests', 'perf2.ts'))
    json.dump(plan, open(os.path.join(out, 'plan.json'), 'w'))
    print('themes prepared:', len(plan['themes']), 'themes,', len(plan['tests']), 'tests,', len(grammars), 'grammars')

main(sys.argv[1], sys.argv[2])
