#!/usr/bin/env python3
"""Generate app/src/main/assets/textmate/manifest.json from the tm-grammars / tm-themes package.

The grammar package carries NO file-extension data, so the extension / filename / first-line
tables below are project-owned (they only map file names to grammar ids; they never describe
tokens). Everything about tokenization comes from the grammar JSON files themselves.

Usage: gen_manifest.py [--pkg DIR_WITH_index.js_parent] [--assets app/src/main/assets/textmate]
"""
import argparse, json, os, subprocess, sys

# id -> (extensions, filenames, firstLine regex)
EXT = {
 'abap': ('.abap',), 'actionscript-3': ('.as',), 'ada': ('.adb .ads .ada',), 'ahk': ('.ahk',), 'ahk2': ('.ahk2',),
 'apache': ('.htaccess .htpasswd', 'httpd.conf apache2.conf .htaccess'), 'apex': ('.apex .trigger',), 'apl': ('.apl',),
 'applescript': ('.applescript .scpt',), 'ara': ('.ara',), 'asciidoc': ('.adoc .asciidoc',), 'asm': ('.asm .s .nasm .masm',),
 'astro': ('.astro',), 'awk': ('.awk',), 'ballerina': ('.bal',), 'bat': ('.bat .cmd',), 'beancount': ('.beancount .bean',),
 'berry': ('.be',), 'bibtex': ('.bib',), 'bicep': ('.bicep',), 'blade': ('.blade.php',), 'bsl': ('.bsl .os',),
 'c': ('.c .h',), 'c3': ('.c3',), 'cadence': ('.cdc',), 'cairo': ('.cairo',), 'chapel': ('.chpl',), 'clarity': ('.clar',),
 'clojure': ('.clj .cljs .cljc .edn',), 'cmake': ('.cmake', 'CMakeLists.txt'), 'cobol': ('.cob .cbl .cobol .cpy',),
 'codeowners': ('', 'CODEOWNERS'), 'codeql': ('.ql .qll',), 'coffee': ('.coffee .cson',), 'common-lisp': ('.lisp .cl .lsp .asd',),
 'coq': ('.coq',), 'cpp': ('.cpp .cc .cxx .c++ .hpp .hh .hxx .h++ .ino .inl .ipp .tpp .cu .cuh',),
 'crystal': ('.cr',), 'csharp': ('.cs .csx',), 'css': ('.css',), 'csv': ('.csv',), 'cue': ('.cue',), 'cypher': ('.cypher .cql',),
 'd': ('.d .di',), 'dart': ('.dart',), 'dax': ('.dax',), 'desktop': ('.desktop',), 'diff': ('.diff .patch .rej',),
 'docker': ('.dockerfile', 'Dockerfile Containerfile'), 'dotenv': ('.env', '.env.local .env.example .env.development .env.production'),
 'dream-maker': ('.dm .dme',), 'edge': ('.edge',), 'elixir': ('.ex .exs',), 'elm': ('.elm',), 'emacs-lisp': ('.el',),
 'erb': ('.erb .rhtml',), 'erlang': ('.erl .hrl',), 'fennel': ('.fnl',), 'fish': ('.fish',), 'fluent': ('.ftl',),
 'fortran-fixed-form': ('.f .for .f77',), 'fortran-free-form': ('.f90 .f95 .f03 .f08',), 'fsharp': ('.fs .fsi .fsx',),
 'gdresource': ('.tres .tscn',), 'gdscript': ('.gd',), 'gdshader': ('.gdshader',), 'genie': ('.gs',), 'gherkin': ('.feature',),
 'git-commit': ('', 'COMMIT_EDITMSG MERGE_MSG TAG_EDITMSG'), 'git-rebase': ('', 'git-rebase-todo'), 'gleam': ('.gleam',),
 'glimmer-js': ('.gjs',), 'glimmer-ts': ('.gts',), 'glsl': ('.glsl .vert .frag .geom .comp .tesc .tese',), 'gn': ('.gn .gni',),
 'gnuplot': ('.gp .plt .gnuplot',), 'go': ('.go',), 'graphql': ('.graphql .gql .graphqls',),
 'groovy': ('.groovy .gradle .gvy .gy .gsh', 'Jenkinsfile'), 'haml': ('.haml',), 'handlebars': ('.hbs .handlebars',),
 'haskell': ('.hs .lhs',), 'haxe': ('.hx',), 'hcl': ('.hcl',), 'hjson': ('.hjson',), 'hlsl': ('.hlsl .fx .fxh',),
 'html': ('.html .htm .xhtml .shtml',), 'http': ('.http .rest',), 'hurl': ('.hurl',), 'hxml': ('.hxml',), 'hy': ('.hy',),
 'imba': ('.imba',), 'ini': ('.ini .cfg .conf .properties .editorconfig .gitconfig .inf .pro', '.gitconfig .editorconfig .npmrc .pylintrc'),
 'java': ('.java .aidl .jav',), 'javascript': ('.js .mjs .cjs .es6 .es',), 'jinja': ('.jinja .jinja2 .j2',), 'jison': ('.jison',),
 'json': ('.json .map .geojson .webmanifest .jsonld .har .ipynb',), 'json5': ('.json5',),
 'jsonc': ('.jsonc .code-workspace .code-snippets', 'tsconfig.json jsconfig.json .eslintrc .babelrc'),
 'jsonl': ('.jsonl .ndjson',), 'jsonnet': ('.jsonnet .libsonnet',), 'jssm': ('.jssm .jssm_state',), 'jsx': ('.jsx',),
 'julia': ('.jl',), 'just': ('.just', 'justfile Justfile'), 'kdl': ('.kdl',), 'kotlin': ('.kt .kts .ktm',), 'kusto': ('.kql .csl',),
 'latex': ('.tex .ltx .sty .cls .latex',), 'lean': ('.lean',), 'less': ('.less',), 'liquid': ('.liquid',), 'llvm': ('.ll',),
 'log': ('.log',), 'logo': ('.logo',), 'lua': ('.lua',), 'luau': ('.luau',),
 'make': ('.mk .mak .make', 'Makefile makefile GNUmakefile'), 'markdown': ('.md .markdown .mdown .mkd .mkdn',),
 'marko': ('.marko',), 'matlab': ('.matlab',), 'mdc': ('.mdc',), 'mdx': ('.mdx',), 'mermaid': ('.mmd .mermaid',),
 'mipsasm': ('.mips',), 'mojo': ('.mojo',), 'moonbit': ('.mbt',), 'move': ('.move',), 'narrat': ('.narrat',),
 'nextflow': ('.nf',), 'nginx': ('.nginx', 'nginx.conf'), 'nim': ('.nim .nims .nimble',), 'nix': ('.nix',), 'nsis': ('.nsi .nsh',),
 'nushell': ('.nu',), 'objective-c': ('.m',), 'objective-cpp': ('.mm',), 'ocaml': ('.ml .mli',), 'odin': ('.odin',),
 'openscad': ('.scad',), 'org': ('.org',), 'pascal': ('.pas .dpr .lpr',), 'perl': ('.pl .pm .t .pod',),
 'php': ('.php .phtml .php3 .php4 .php5 .phps',), 'pkl': ('.pkl',), 'plsql': ('.pks .pkb .plsql',), 'po': ('.po .pot',),
 'polar': ('.polar',), 'postcss': ('.pcss .postcss',), 'powerquery': ('.pq',), 'powershell': ('.ps1 .psm1 .psd1',),
 'prisma': ('.prisma',), 'prolog': ('.prolog',), 'proto': ('.proto',), 'pug': ('.pug .jade',), 'puppet': ('.pp',),
 'purescript': ('.purs',), 'python': ('.py .pyw .pyi .pyx .pxd .gyp .gypi .rpy', 'SConstruct SConscript'), 'qml': ('.qml',),
 'qmldir': ('', 'qmldir'), 'qss': ('.qss',), 'r': ('.r .rprofile',), 'racket': ('.rkt',), 'raku': ('.raku .rakumod .p6 .pl6',),
 'razor': ('.cshtml .razor',), 'rbs': ('.rbs',), 'reg': ('.reg',), 'rel': ('.rel',), 'riscv': ('.riscv',), 'ron': ('.ron',),
 'rosmsg': ('.msg .srv',), 'rst': ('.rst',), 'ruby': ('.rb .rake .gemspec .ru .erb.rb .podspec', 'Gemfile Rakefile Podfile Fastfile Vagrantfile'),
 'rust': ('.rs',), 'sas': ('.sas',), 'sass': ('.sass',), 'scala': ('.scala .sbt .sc',), 'scheme': ('.scm .ss .sld',), 'scss': ('.scss',),
 'sdbl': ('.sdbl',), 'shaderlab': ('.shader',), 'shellscript': ('.sh .bash .zsh .ksh .command .bashrc .zshrc .profile', '.bashrc .zshrc .profile .bash_profile .bash_aliases'),
 'smalltalk': ('.st',), 'smithy': ('.smithy',), 'solidity': ('.sol',), 'soy': ('.soy',), 'sparql': ('.rq .sparql',),
 'sql': ('.sql .ddl .dml',), 'ssh-config': ('', 'ssh_config sshd_config'), 'stata': ('.do .ado',), 'stylus': ('.styl .stylus',),
 'surrealql': ('.surql',), 'svelte': ('.svelte',), 'swift': ('.swift',), 'system-verilog': ('.sv .svh',),
 'systemd': ('.service .timer .socket .mount .target',), 'talonscript': ('.talon',), 'tasl': ('.tasl',), 'tcl': ('.tcl',),
 'templ': ('.templ',), 'terraform': ('.tf .tfvars',), 'tex': ('.texi',), 'toml': ('.toml', 'Cargo.lock Pipfile'), 'tsv': ('.tsv .tab',),
 'tsx': ('.tsx',), 'turtle': ('.ttl',), 'twig': ('.twig',), 'typescript': ('.ts .mts .cts',), 'typespec': ('.tsp',), 'typst': ('.typ',),
 'v': ('.vv',), 'vala': ('.vala .vapi',), 'vb': ('.vb .bas .vbs',), 'verilog': ('.v .vh',), 'vhdl': ('.vhd .vhdl .vho',),
 'viml': ('.vim .vimrc',), 'vue': ('.vue',), 'vyper': ('.vy',), 'wasm': ('.wat .wast',), 'wenyan': ('.wy',), 'wgsl': ('.wgsl',),
 'wikitext': ('.wiki .mediawiki',), 'wit': ('.wit',), 'wolfram': ('.wl .wls .nb',),
 'xml': ('.xml .xsd .xaml .svg .plist .csproj .vbproj .fsproj .props .targets .resx .rss .atom .kml .gpx .wsdl .xib .storyboard .axml .dtd .tmLanguage .tmTheme .vcxproj .nuspec', 'AndroidManifest.xml'),
 'xsl': ('.xsl .xslt',), 'yaml': ('.yml .yaml .clang-format .clang-tidy', '.clang-format'), 'zenscript': ('.zs',), 'zig': ('.zig .zon',),
}

# first-line (shebang / prolog) detection for files without a known extension
FIRST_LINE = {
 'python': r'^#!.*\bpython[0-9.]*\b', 'shellscript': r'^#!.*\b(ba|z|k|da|a)?sh\b', 'javascript': r'^#!.*\b(node|deno|bun)\b',
 'ruby': r'^#!.*\bruby\b', 'perl': r'^#!.*\bperl\b', 'php': r'^<\?php', 'xml': r'^<\?xml\b', 'html': r'^\s*<!doctype\s+html|^\s*<html\b',
 'lua': r'^#!.*\blua\b', 'powershell': r'^#!.*\bpwsh\b', 'diff': r'^(diff --git |--- .+\n\+\+\+ |Index: )', 'make': r'^#!.*\bmake\b',
}

# grammars that exist only as injections / embedded helpers: hidden from the language picker
HIDDEN_PREFIXES = ('angular-', 'es-tag-', 'vue-directives', 'vue-interpolations', 'vue-sfc-', 'markdown-nix', 'markdown-vue',
                   'cpp-macro', 'html-derivative', 'ts-tags', 'regexp', 'vue-html', 'vue-vine')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pkg', default='/home/claude/work/tm-themes/textmate-grammars-themes-main/packages')
    ap.add_argument('--assets', default=os.path.join(os.path.dirname(__file__), '..', 'app/src/main/assets/textmate'))
    a = ap.parse_args()
    assets = os.path.abspath(a.assets)

    def load_index(sub):
        js = "import('%s/%s/index.js').then(m=>console.log(JSON.stringify(Object.values(m)[0])))" % (a.pkg, sub)
        return json.loads(subprocess.check_output(['node', '-e', js]))
    gidx = {g['name']: g for g in load_index('tm-grammars')}
    tidx = {t['name']: t for t in load_index('tm-themes')}
    pk = lambda s: json.load(open(os.path.join(a.pkg, s, 'package.json')))
    gp, tp = pk('tm-grammars'), pk('tm-themes')

    langs = []
    gdir = os.path.join(assets, 'grammars')
    for fn in sorted(os.listdir(gdir)):
        if not fn.endswith('.json'):
            continue
        gid = fn[:-5]
        d = json.load(open(os.path.join(gdir, fn), encoding='utf-8'))
        meta = gidx.get(gid, {})
        ext, names = (EXT.get(gid, ('',)) + ('',))[:2]
        e = {
            'id': gid, 'name': d.get('displayName') or meta.get('displayName') or gid, 'scope': d['scopeName'], 'file': fn,
            'aliases': meta.get('aliases', []),
            'extensions': ext.split(), 'filenames': names.split(),
        }
        if gid in FIRST_LINE: e['firstLine'] = FIRST_LINE[gid]
        if meta.get('embedded'): e['embedded'] = meta['embedded']
        if d.get('injectTo'): e['injectTo'] = d['injectTo']
        if d.get('injectTo') or gid.startswith(HIDDEN_PREFIXES): e['hidden'] = True
        if meta.get('license'): e['license'] = meta['license']
        langs.append(e)
    scopes = {}
    for l in langs:
        scopes.setdefault(l['scope'], []).append(l['id'])
    dups = {s: v for s, v in scopes.items() if len(v) > 1}
    if dups: print('WARN duplicate scopes:', dups, file=sys.stderr)

    themes = []
    tdir = os.path.join(assets, 'themes')
    for fn in sorted(os.listdir(tdir)):
        tid = fn[:-5]
        meta = tidx.get(tid, {})
        d = json.load(open(os.path.join(tdir, fn), encoding='utf-8'))
        themes.append({'id': tid, 'name': meta.get('displayName') or d.get('name') or tid,
                       'type': meta.get('type') or d.get('type') or 'dark', 'file': fn, 'license': meta.get('license', '')})

    man = {
        'format': 1,
        'grammarPackage': {'name': gp['name'], 'version': gp['version'], 'license': gp['license'], 'homepage': gp.get('homepage', '')},
        'themePackage': {'name': tp['name'], 'version': tp['version'], 'license': tp['license'], 'homepage': tp.get('homepage', '')},
        'engine': 'vscode-textmate 9.3.2 (C++ port) + Oniguruma',
        'languages': langs, 'themes': themes,
    }
    with open(os.path.join(assets, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(man, f, ensure_ascii=False, separators=(',', ':'))
    print('languages', len(langs), 'themes', len(themes), 'with-ext', sum(1 for l in langs if l['extensions'] or l['filenames']))


if __name__ == '__main__':
    main()
