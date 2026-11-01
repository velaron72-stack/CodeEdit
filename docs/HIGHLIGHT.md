# Syntax highlighting

All highlighting comes from the bundled TextMate package, executed by a native engine. There is **no hand-written lexer** for any
language in the package.

| Part | Version | Where |
|---|---|---|
| Grammars | `tm-grammars` 1.32.22 (260 grammars, MIT) | `app/src/main/assets/textmate/grammars/*.json` |
| Themes | `tm-themes` 1.12.12 ([{'id': 'andromeeda', 'name': 'Andromeeda', 'type': 'dark', 'file': 'andromeeda.json', 'license': 'MIT'}, {'id': 'aurora-x', 'name': 'Aurora X', 'type': 'dark', 'file': 'aurora-x.json', 'license': 'GPL-3.0'}, {'id': 'ayu-dark', 'name': 'Ayu Dark', 'type': 'dark', 'file': 'ayu-dark.json', 'license': 'MIT'}, {'id': 'ayu-light', 'name': 'Ayu Light', 'type': 'light', 'file': 'ayu-light.json', 'license': 'MIT'}, {'id': 'ayu-mirage', 'name': 'Ayu Mirage', 'type': 'dark', 'file': 'ayu-mirage.json', 'license': 'MIT'}, {'id': 'catppuccin-frappe', 'name': 'Catppuccin Frappé', 'type': 'dark', 'file': 'catppuccin-frappe.json', 'license': 'MIT'}, {'id': 'catppuccin-latte', 'name': 'Catppuccin Latte', 'type': 'light', 'file': 'catppuccin-latte.json', 'license': 'MIT'}, {'id': 'catppuccin-macchiato', 'name': 'Catppuccin Macchiato', 'type': 'dark', 'file': 'catppuccin-macchiato.json', 'license': 'MIT'}, {'id': 'catppuccin-mocha', 'name': 'Catppuccin Mocha', 'type': 'dark', 'file': 'catppuccin-mocha.json', 'license': 'MIT'}, {'id': 'dark-plus', 'name': 'Dark Plus', 'type': 'dark', 'file': 'dark-plus.json', 'license': 'MIT'}, {'id': 'dracula-soft', 'name': 'Dracula Theme Soft', 'type': 'dark', 'file': 'dracula-soft.json', 'license': 'MIT'}, {'id': 'dracula', 'name': 'Dracula Theme', 'type': 'dark', 'file': 'dracula.json', 'license': 'MIT'}, {'id': 'everforest-dark', 'name': 'Everforest Dark', 'type': 'dark', 'file': 'everforest-dark.json', 'license': 'MIT'}, {'id': 'everforest-light', 'name': 'Everforest Light', 'type': 'light', 'file': 'everforest-light.json', 'license': 'MIT'}, {'id': 'github-dark-default', 'name': 'GitHub Dark Default', 'type': 'dark', 'file': 'github-dark-default.json', 'license': 'MIT'}, {'id': 'github-dark-dimmed', 'name': 'GitHub Dark Dimmed', 'type': 'dark', 'file': 'github-dark-dimmed.json', 'license': 'MIT'}, {'id': 'github-dark-high-contrast', 'name': 'GitHub Dark High Contrast', 'type': 'dark', 'file': 'github-dark-high-contrast.json', 'license': 'MIT'}, {'id': 'github-dark', 'name': 'GitHub Dark', 'type': 'dark', 'file': 'github-dark.json', 'license': 'MIT'}, {'id': 'github-light-default', 'name': 'GitHub Light Default', 'type': 'light', 'file': 'github-light-default.json', 'license': 'MIT'}, {'id': 'github-light-high-contrast', 'name': 'GitHub Light High Contrast', 'type': 'light', 'file': 'github-light-high-contrast.json', 'license': 'MIT'}, {'id': 'github-light', 'name': 'GitHub Light', 'type': 'light', 'file': 'github-light.json', 'license': 'MIT'}, {'id': 'gruvbox-dark-hard', 'name': 'Gruvbox Dark Hard', 'type': 'dark', 'file': 'gruvbox-dark-hard.json', 'license': 'MIT'}, {'id': 'gruvbox-dark-medium', 'name': 'Gruvbox Dark Medium', 'type': 'dark', 'file': 'gruvbox-dark-medium.json', 'license': 'MIT'}, {'id': 'gruvbox-dark-soft', 'name': 'Gruvbox Dark Soft', 'type': 'dark', 'file': 'gruvbox-dark-soft.json', 'license': 'MIT'}, {'id': 'gruvbox-light-hard', 'name': 'Gruvbox Light Hard', 'type': 'light', 'file': 'gruvbox-light-hard.json', 'license': 'MIT'}, {'id': 'gruvbox-light-medium', 'name': 'Gruvbox Light Medium', 'type': 'light', 'file': 'gruvbox-light-medium.json', 'license': 'MIT'}, {'id': 'gruvbox-light-soft', 'name': 'Gruvbox Light Soft', 'type': 'light', 'file': 'gruvbox-light-soft.json', 'license': 'MIT'}, {'id': 'horizon-bright', 'name': 'Horizon Bright', 'type': 'light', 'file': 'horizon-bright.json', 'license': 'MIT'}, {'id': 'horizon', 'name': 'Horizon', 'type': 'dark', 'file': 'horizon.json', 'license': 'MIT'}, {'id': 'houston', 'name': 'Houston', 'type': 'dark', 'file': 'houston.json', 'license': 'MIT'}, {'id': 'kanagawa-dragon', 'name': 'Kanagawa Dragon', 'type': 'dark', 'file': 'kanagawa-dragon.json', 'license': 'MIT'}, {'id': 'kanagawa-lotus', 'name': 'Kanagawa Lotus', 'type': 'light', 'file': 'kanagawa-lotus.json', 'license': 'MIT'}, {'id': 'kanagawa-wave', 'name': 'Kanagawa Wave', 'type': 'dark', 'file': 'kanagawa-wave.json', 'license': 'MIT'}, {'id': 'laserwave', 'name': 'LaserWave', 'type': 'dark', 'file': 'laserwave.json', 'license': 'MIT'}, {'id': 'light-plus', 'name': 'Light Plus', 'type': 'light', 'file': 'light-plus.json', 'license': 'MIT'}, {'id': 'material-theme-darker', 'name': 'Material Theme Darker', 'type': 'dark', 'file': 'material-theme-darker.json', 'license': 'Apache-2.0'}, {'id': 'material-theme-lighter', 'name': 'Material Theme Lighter', 'type': 'light', 'file': 'material-theme-lighter.json', 'license': 'Apache-2.0'}, {'id': 'material-theme-ocean', 'name': 'Material Theme Ocean', 'type': 'dark', 'file': 'material-theme-ocean.json', 'license': 'Apache-2.0'}, {'id': 'material-theme-palenight', 'name': 'Material Theme Palenight', 'type': 'dark', 'file': 'material-theme-palenight.json', 'license': 'Apache-2.0'}, {'id': 'material-theme', 'name': 'Material Theme', 'type': 'dark', 'file': 'material-theme.json', 'license': 'Apache-2.0'}, {'id': 'min-dark', 'name': 'Min Dark', 'type': 'dark', 'file': 'min-dark.json', 'license': 'MIT'}, {'id': 'min-light', 'name': 'Min Light', 'type': 'light', 'file': 'min-light.json', 'license': 'MIT'}, {'id': 'monokai', 'name': 'Monokai', 'type': 'dark', 'file': 'monokai.json', 'license': 'MIT'}, {'id': 'night-owl-light', 'name': 'Night Owl Light', 'type': 'light', 'file': 'night-owl-light.json', 'license': 'MIT'}, {'id': 'night-owl', 'name': 'Night Owl', 'type': 'dark', 'file': 'night-owl.json', 'license': 'MIT'}, {'id': 'nord', 'name': 'Nord', 'type': 'dark', 'file': 'nord.json', 'license': 'MIT'}, {'id': 'one-dark-pro', 'name': 'One Dark Pro', 'type': 'dark', 'file': 'one-dark-pro.json', 'license': 'MIT'}, {'id': 'one-light', 'name': 'One Light', 'type': 'light', 'file': 'one-light.json', 'license': 'MIT'}, {'id': 'plastic', 'name': 'Plastic', 'type': 'dark', 'file': 'plastic.json', 'license': 'MIT'}, {'id': 'poimandres', 'name': 'Poimandres', 'type': 'dark', 'file': 'poimandres.json', 'license': 'MIT'}, {'id': 'red', 'name': 'Red', 'type': 'dark', 'file': 'red.json', 'license': 'MIT'}, {'id': 'rose-pine-dawn', 'name': 'Rosé Pine Dawn', 'type': 'light', 'file': 'rose-pine-dawn.json', 'license': 'MIT'}, {'id': 'rose-pine-moon', 'name': 'Rosé Pine Moon', 'type': 'dark', 'file': 'rose-pine-moon.json', 'license': 'MIT'}, {'id': 'rose-pine', 'name': 'Rosé Pine', 'type': 'dark', 'file': 'rose-pine.json', 'license': 'MIT'}, {'id': 'slack-dark', 'name': 'Slack Dark', 'type': 'dark', 'file': 'slack-dark.json', 'license': 'MIT'}, {'id': 'slack-ochin', 'name': 'Slack Ochin', 'type': 'light', 'file': 'slack-ochin.json', 'license': 'MIT'}, {'id': 'snazzy-light', 'name': 'Snazzy Light', 'type': 'light', 'file': 'snazzy-light.json', 'license': 'MIT'}, {'id': 'solarized-dark', 'name': 'Solarized Dark', 'type': 'dark', 'file': 'solarized-dark.json', 'license': 'MIT'}, {'id': 'solarized-light', 'name': 'Solarized Light', 'type': 'light', 'file': 'solarized-light.json', 'license': 'MIT'}, {'id': 'synthwave-84', 'name': "Synthwave '84", 'type': 'dark', 'file': 'synthwave-84.json', 'license': 'MIT'}, {'id': 'tokyo-night', 'name': 'Tokyo Night', 'type': 'dark', 'file': 'tokyo-night.json', 'license': 'MIT'}, {'id': 'vesper', 'name': 'Vesper', 'type': 'dark', 'file': 'vesper.json', 'license': 'MIT'}, {'id': 'vitesse-black', 'name': 'Vitesse Black', 'type': 'dark', 'file': 'vitesse-black.json', 'license': 'MIT'}, {'id': 'vitesse-dark', 'name': 'Vitesse Dark', 'type': 'dark', 'file': 'vitesse-dark.json', 'license': 'MIT'}, {'id': 'vitesse-light', 'name': 'Vitesse Light', 'type': 'light', 'file': 'vitesse-light.json', 'license': 'MIT'}] themes, MIT) | `app/src/main/assets/textmate/themes/*.json` |
| Engine | C++17 port of `vscode-textmate` 9.3.2 | `app/src/main/cpp/tm_*.cpp` |
| Regex | Oniguruma 6.9.10 (BSD-2) | `app/src/main/cpp/third_party/oniguruma` |
| Index | `assets/textmate/manifest.json` (generated by `tools/gen_manifest.py`) | language id, scope, file, extensions, filenames |

Language detection (`LanguageDetector`): exact file name, then longest extension, then first-line pattern, using only the data in the
manifest. The user can override the language per tab (status bar -> language).

## Not in the package

* **Smali** — `tm-grammars` has no Smali grammar. Smali files open as plain text.
* **.gitignore** — no grammar either; plain text.

Nothing is faked for these: they are reported as "no grammar".

## Known limitations

* Cross-grammar `include`s and injections between grammars that are in the package work. The `embeddedLanguages` map of VS Code (language id
  of an embedded scope, used for editor features like comment toggling) is not implemented; highlighting does not need it.
* Very long lines (default > 10 000 chars, setting `hl.maxLineChars`) are not tokenized (single plain token).
* Files larger than `hl.maxFileMb` are not highlighted. Large files are tokenized lazily around the viewport from checkpoints;
  for a jump to a far line the first paint uses the nearest checkpoint (can be slightly off until the frontier catches up).

## Bundled languages (260)

| Language | id | grammar file | extensions / filenames |
|---|---|---|---|
| 1C (Enterprise) | `bsl` | `grammars/bsl.json` | .bsl .os |
| 1C (Query) | `sdbl` | `grammars/sdbl.json` | .sdbl |
| ABAP | `abap` | `grammars/abap.json` | .abap |
| ActionScript | `actionscript-3` | `grammars/actionscript-3.json` | .as |
| Ada | `ada` | `grammars/ada.json` | .adb .ads .ada |
| Angular HTML | `angular-html` | `grammars/angular-html.json` |  |
| Angular TypeScript | `angular-ts` | `grammars/angular-ts.json` |  |
| angular-expression | `angular-expression` | `grammars/angular-expression.json` |  |
| angular-inline-style | `angular-inline-style` | `grammars/angular-inline-style.json` |  |
| angular-inline-template | `angular-inline-template` | `grammars/angular-inline-template.json` |  |
| angular-let-declaration | `angular-let-declaration` | `grammars/angular-let-declaration.json` |  |
| angular-template | `angular-template` | `grammars/angular-template.json` |  |
| angular-template-blocks | `angular-template-blocks` | `grammars/angular-template-blocks.json` |  |
| Apache Conf | `apache` | `grammars/apache.json` | .htaccess .htpasswd httpd.conf apache2.conf .htaccess |
| Apex | `apex` | `grammars/apex.json` | .apex .trigger |
| APL | `apl` | `grammars/apl.json` | .apl |
| AppleScript | `applescript` | `grammars/applescript.json` | .applescript .scpt |
| Ara | `ara` | `grammars/ara.json` | .ara |
| AsciiDoc | `asciidoc` | `grammars/asciidoc.json` | .adoc .asciidoc |
| ASP.NET Razor | `razor` | `grammars/razor.json` | .cshtml .razor |
| Assembly | `asm` | `grammars/asm.json` | .asm .s .nasm .masm |
| Astro | `astro` | `grammars/astro.json` | .astro |
| AutoHotkey | `ahk` | `grammars/ahk.json` | .ahk |
| AutoHotkey2 | `ahk2` | `grammars/ahk2.json` | .ahk2 |
| AWK | `awk` | `grammars/awk.json` | .awk |
| Ballerina | `ballerina` | `grammars/ballerina.json` | .bal |
| Batch File | `bat` | `grammars/bat.json` | .bat .cmd |
| Beancount | `beancount` | `grammars/beancount.json` | .beancount .bean |
| Berry | `berry` | `grammars/berry.json` | .be |
| BibTeX | `bibtex` | `grammars/bibtex.json` | .bib |
| Bicep | `bicep` | `grammars/bicep.json` | .bicep |
| BIRD2 Configuration | `bird2` | `grammars/bird2.json` |  |
| Blade | `blade` | `grammars/blade.json` | .blade.php |
| C | `c` | `grammars/c.json` | .c .h |
| C# | `csharp` | `grammars/csharp.json` | .cs .csx |
| C++ | `cpp-macro` | `grammars/cpp-macro.json` |  |
| C++ | `cpp` | `grammars/cpp.json` | .cpp .cc .cxx .c++ .hpp .hh |
| C3 | `c3` | `grammars/c3.json` | .c3 |
| Cadence | `cadence` | `grammars/cadence.json` | .cdc |
| Cairo | `cairo` | `grammars/cairo.json` | .cairo |
| Chapel | `chapel` | `grammars/chapel.json` | .chpl |
| Clarity | `clarity` | `grammars/clarity.json` | .clar |
| Clojure | `clojure` | `grammars/clojure.json` | .clj .cljs .cljc .edn |
| Closure Templates | `soy` | `grammars/soy.json` | .soy |
| CMake | `cmake` | `grammars/cmake.json` | .cmake CMakeLists.txt |
| COBOL | `cobol` | `grammars/cobol.json` | .cob .cbl .cobol .cpy |
| CODEOWNERS | `codeowners` | `grammars/codeowners.json` | CODEOWNERS |
| CodeQL | `codeql` | `grammars/codeql.json` | .ql .qll |
| CoffeeScript | `coffee` | `grammars/coffee.json` | .coffee .cson |
| Common Lisp | `common-lisp` | `grammars/common-lisp.json` | .lisp .cl .lsp .asd |
| Crystal | `crystal` | `grammars/crystal.json` | .cr |
| CSS | `css` | `grammars/css.json` | .css |
| CSV | `csv` | `grammars/csv.json` | .csv |
| CUE | `cue` | `grammars/cue.json` | .cue |
| Cypher | `cypher` | `grammars/cypher.json` | .cypher .cql |
| D | `d` | `grammars/d.json` | .d .di |
| Dart | `dart` | `grammars/dart.json` | .dart |
| DAX | `dax` | `grammars/dax.json` | .dax |
| Desktop | `desktop` | `grammars/desktop.json` | .desktop |
| Diff | `diff` | `grammars/diff.json` | .diff .patch .rej |
| Dockerfile | `docker` | `grammars/docker.json` | .dockerfile Dockerfile Containerfile |
| dotEnv | `dotenv` | `grammars/dotenv.json` | .env .env.local .env.example .env.development .env.production |
| Dream Maker | `dream-maker` | `grammars/dream-maker.json` | .dm .dme |
| Edge | `edge` | `grammars/edge.json` | .edge |
| Elixir | `elixir` | `grammars/elixir.json` | .ex .exs |
| Elm | `elm` | `grammars/elm.json` | .elm |
| Emacs Lisp | `emacs-lisp` | `grammars/emacs-lisp.json` | .el |
| ERB | `erb` | `grammars/erb.json` | .erb .rhtml |
| Erlang | `erlang` | `grammars/erlang.json` | .erl .hrl |
| es-tag-css | `es-tag-css` | `grammars/es-tag-css.json` |  |
| es-tag-glsl | `es-tag-glsl` | `grammars/es-tag-glsl.json` |  |
| es-tag-html | `es-tag-html` | `grammars/es-tag-html.json` |  |
| es-tag-sql | `es-tag-sql` | `grammars/es-tag-sql.json` |  |
| es-tag-xml | `es-tag-xml` | `grammars/es-tag-xml.json` |  |
| F# | `fsharp` | `grammars/fsharp.json` | .fs .fsi .fsx |
| Fennel | `fennel` | `grammars/fennel.json` | .fnl |
| Fish | `fish` | `grammars/fish.json` | .fish |
| Fluent | `fluent` | `grammars/fluent.json` | .ftl |
| Fortran (Fixed Form) | `fortran-fixed-form` | `grammars/fortran-fixed-form.json` | .f .for .f77 |
| Fortran (Free Form) | `fortran-free-form` | `grammars/fortran-free-form.json` | .f90 .f95 .f03 .f08 |
| GDResource | `gdresource` | `grammars/gdresource.json` | .tres .tscn |
| GDScript | `gdscript` | `grammars/gdscript.json` | .gd |
| GDShader | `gdshader` | `grammars/gdshader.json` | .gdshader |
| Genie | `genie` | `grammars/genie.json` | .gs |
| Gettext PO | `po` | `grammars/po.json` | .po .pot |
| Gherkin | `gherkin` | `grammars/gherkin.json` | .feature |
| Git Commit Message | `git-commit` | `grammars/git-commit.json` | COMMIT_EDITMSG MERGE_MSG TAG_EDITMSG |
| Git Rebase Message | `git-rebase` | `grammars/git-rebase.json` | git-rebase-todo |
| Gleam | `gleam` | `grammars/gleam.json` | .gleam |
| Glimmer JS | `glimmer-js` | `grammars/glimmer-js.json` | .gjs |
| Glimmer TS | `glimmer-ts` | `grammars/glimmer-ts.json` | .gts |
| GLSL | `glsl` | `grammars/glsl.json` | .glsl .vert .frag .geom .comp .tesc |
| GN | `gn` | `grammars/gn.json` | .gn .gni |
| GNU Smalltalk | `smalltalk` | `grammars/smalltalk.json` | .st |
| Gnuplot | `gnuplot` | `grammars/gnuplot.json` | .gp .plt .gnuplot |
| Go | `go` | `grammars/go.json` | .go |
| GraphQL | `graphql` | `grammars/graphql.json` | .graphql .gql .graphqls |
| Groovy | `groovy` | `grammars/groovy.json` | .groovy .gradle .gvy .gy .gsh Jenkinsfile |
| Hack | `hack` | `grammars/hack.json` |  |
| Handlebars | `handlebars` | `grammars/handlebars.json` | .hbs .handlebars |
| HashiCorp HCL | `hcl` | `grammars/hcl.json` | .hcl |
| Haskell | `haskell` | `grammars/haskell.json` | .hs .lhs |
| Haxe | `haxe` | `grammars/haxe.json` | .hx |
| Hjson | `hjson` | `grammars/hjson.json` | .hjson |
| HLSL | `hlsl` | `grammars/hlsl.json` | .hlsl .fx .fxh |
| HTML | `html` | `grammars/html.json` | .html .htm .xhtml .shtml |
| HTML (Derivative) | `html-derivative` | `grammars/html-derivative.json` |  |
| HTTP | `http` | `grammars/http.json` | .http .rest |
| Hurl | `hurl` | `grammars/hurl.json` | .hurl |
| HXML | `hxml` | `grammars/hxml.json` | .hxml |
| Hy | `hy` | `grammars/hy.json` | .hy |
| Imba | `imba` | `grammars/imba.json` | .imba |
| INI | `ini` | `grammars/ini.json` | .ini .cfg .conf .properties .editorconfig .gitconfig .gitconfig .editorconfig .npmrc .pylintrc |
| Java | `java` | `grammars/java.json` | .java .aidl .jav |
| JavaScript | `javascript` | `grammars/javascript.json` | .js .mjs .cjs .es6 .es |
| Jinja | `jinja` | `grammars/jinja.json` | .jinja .jinja2 .j2 |
| jinja-html | `jinja-html` | `grammars/jinja-html.json` |  |
| Jison | `jison` | `grammars/jison.json` | .jison |
| JSON | `json` | `grammars/json.json` | .json .map .geojson .webmanifest .jsonld .har |
| JSON Lines | `jsonl` | `grammars/jsonl.json` | .jsonl .ndjson |
| JSON with Comments | `jsonc` | `grammars/jsonc.json` | .jsonc .code-workspace .code-snippets tsconfig.json jsconfig.json .eslintrc .babelrc |
| JSON5 | `json5` | `grammars/json5.json` | .json5 |
| Jsonnet | `jsonnet` | `grammars/jsonnet.json` | .jsonnet .libsonnet |
| JSSM | `jssm` | `grammars/jssm.json` | .jssm .jssm_state |
| JSX | `jsx` | `grammars/jsx.json` | .jsx |
| Julia | `julia` | `grammars/julia.json` | .jl |
| Just | `just` | `grammars/just.json` | .just justfile Justfile |
| KDL | `kdl` | `grammars/kdl.json` | .kdl |
| Kotlin | `kotlin` | `grammars/kotlin.json` | .kt .kts .ktm |
| Kusto | `kusto` | `grammars/kusto.json` | .kql .csl |
| LaTeX | `latex` | `grammars/latex.json` | .tex .ltx .sty .cls .latex |
| Lean 4 | `lean` | `grammars/lean.json` | .lean |
| Less | `less` | `grammars/less.json` | .less |
| Liquid | `liquid` | `grammars/liquid.json` | .liquid |
| LLVM IR | `llvm` | `grammars/llvm.json` | .ll |
| Log file | `log` | `grammars/log.json` | .log |
| Logo | `logo` | `grammars/logo.json` | .logo |
| Lua | `lua` | `grammars/lua.json` | .lua |
| Luau | `luau` | `grammars/luau.json` | .luau |
| Makefile | `make` | `grammars/make.json` | .mk .mak .make Makefile makefile GNUmakefile |
| Markdown | `markdown` | `grammars/markdown.json` | .md .markdown .mdown .mkd .mkdn |
| markdown-nix | `markdown-nix` | `grammars/markdown-nix.json` |  |
| markdown-vue | `markdown-vue` | `grammars/markdown-vue.json` |  |
| Marko | `marko` | `grammars/marko.json` | .marko |
| MATLAB | `matlab` | `grammars/matlab.json` | .matlab |
| MDC | `mdc` | `grammars/mdc.json` | .mdc |
| MDX | `mdx` | `grammars/mdx.json` | .mdx |
| Mermaid | `mermaid` | `grammars/mermaid.json` | .mmd .mermaid |
| MIPS Assembly | `mipsasm` | `grammars/mipsasm.json` | .mips |
| Mojo | `mojo` | `grammars/mojo.json` | .mojo |
| MoonBit | `moonbit` | `grammars/moonbit.json` | .mbt |
| Move | `move` | `grammars/move.json` | .move |
| Narrat Language | `narrat` | `grammars/narrat.json` | .narrat |
| Nextflow | `nextflow` | `grammars/nextflow.json` | .nf |
| Nextflow Groovy | `nextflow-groovy` | `grammars/nextflow-groovy.json` |  |
| Nginx | `nginx` | `grammars/nginx.json` | .nginx nginx.conf |
| Nim | `nim` | `grammars/nim.json` | .nim .nims .nimble |
| Nix | `nix` | `grammars/nix.json` | .nix |
| NSIS | `nsis` | `grammars/nsis.json` | .nsi .nsh |
| nushell | `nushell` | `grammars/nushell.json` | .nu |
| Objective-C | `objective-c` | `grammars/objective-c.json` | .m |
| Objective-C++ | `objective-cpp` | `grammars/objective-cpp.json` | .mm |
| OCaml | `ocaml` | `grammars/ocaml.json` | .ml .mli |
| Odin | `odin` | `grammars/odin.json` | .odin |
| OpenSCAD | `openscad` | `grammars/openscad.json` | .scad |
| Org Markup | `org` | `grammars/org.json` | .org |
| Pascal | `pascal` | `grammars/pascal.json` | .pas .dpr .lpr |
| Perl | `perl` | `grammars/perl.json` | .pl .pm .t .pod |
| PHP | `php` | `grammars/php.json` | .php .phtml .php3 .php4 .php5 .phps |
| Pkl | `pkl` | `grammars/pkl.json` | .pkl |
| PL/SQL | `plsql` | `grammars/plsql.json` | .pks .pkb .plsql |
| Polar | `polar` | `grammars/polar.json` | .polar |
| PostCSS | `postcss` | `grammars/postcss.json` | .pcss .postcss |
| PowerQuery | `powerquery` | `grammars/powerquery.json` | .pq |
| PowerShell | `powershell` | `grammars/powershell.json` | .ps1 .psm1 .psd1 |
| Prisma | `prisma` | `grammars/prisma.json` | .prisma |
| Prolog | `prolog` | `grammars/prolog.json` | .prolog |
| Protocol Buffer 3 | `proto` | `grammars/proto.json` | .proto |
| Pug | `pug` | `grammars/pug.json` | .pug .jade |
| Puppet | `puppet` | `grammars/puppet.json` | .pp |
| PureScript | `purescript` | `grammars/purescript.json` | .purs |
| Python | `python` | `grammars/python.json` | .py .pyw .pyi .pyx .pxd .gyp SConstruct SConscript |
| QML | `qml` | `grammars/qml.json` | .qml |
| QML Directory | `qmldir` | `grammars/qmldir.json` | qmldir |
| Qt Style Sheets | `qss` | `grammars/qss.json` | .qss |
| R | `r` | `grammars/r.json` | .r .rprofile |
| Racket | `racket` | `grammars/racket.json` | .rkt |
| Raku | `raku` | `grammars/raku.json` | .raku .rakumod .p6 .pl6 |
| RBS | `rbs` | `grammars/rbs.json` | .rbs |
| RegExp | `regexp` | `grammars/regexp.json` |  |
| Rel | `rel` | `grammars/rel.json` | .rel |
| reStructuredText | `rst` | `grammars/rst.json` | .rst |
| RISC-V | `riscv` | `grammars/riscv.json` | .riscv |
| Rocq | `coq` | `grammars/coq.json` | .coq |
| RON | `ron` | `grammars/ron.json` | .ron |
| ROS Interface | `rosmsg` | `grammars/rosmsg.json` | .msg .srv |
| Ruby | `ruby` | `grammars/ruby.json` | .rb .rake .gemspec .ru .erb.rb .podspec Gemfile Rakefile Podfile Fastfile |
| Ruby Haml | `haml` | `grammars/haml.json` | .haml |
| Rust | `rust` | `grammars/rust.json` | .rs |
| SAS | `sas` | `grammars/sas.json` | .sas |
| Sass | `sass` | `grammars/sass.json` | .sass |
| Scala | `scala` | `grammars/scala.json` | .scala .sbt .sc |
| Scheme | `scheme` | `grammars/scheme.json` | .scm .ss .sld |
| SCSS | `scss` | `grammars/scss.json` | .scss |
| ShaderLab | `shaderlab` | `grammars/shaderlab.json` | .shader |
| Shell | `shellscript` | `grammars/shellscript.json` | .sh .bash .zsh .ksh .command .bashrc .bashrc .zshrc .profile .bash_profile |
| Shell Session | `shellsession` | `grammars/shellsession.json` |  |
| Smithy | `smithy` | `grammars/smithy.json` | .smithy |
| Solidity | `solidity` | `grammars/solidity.json` | .sol |
| SPARQL | `sparql` | `grammars/sparql.json` | .rq .sparql |
| Splunk Query Language | `splunk` | `grammars/splunk.json` |  |
| SQL | `sql` | `grammars/sql.json` | .sql .ddl .dml |
| SSH Config | `ssh-config` | `grammars/ssh-config.json` | ssh_config sshd_config |
| Stata | `stata` | `grammars/stata.json` | .do .ado |
| Stylus | `stylus` | `grammars/stylus.json` | .styl .stylus |
| SurrealQL | `surrealql` | `grammars/surrealql.json` | .surql |
| Svelte | `svelte` | `grammars/svelte.json` | .svelte |
| Swift | `swift` | `grammars/swift.json` | .swift |
| Systemd Units | `systemd` | `grammars/systemd.json` | .service .timer .socket .mount .target |
| SystemVerilog | `system-verilog` | `grammars/system-verilog.json` | .sv .svh |
| TalonScript | `talonscript` | `grammars/talonscript.json` | .talon |
| Tasl | `tasl` | `grammars/tasl.json` | .tasl |
| Tcl | `tcl` | `grammars/tcl.json` | .tcl |
| Templ | `templ` | `grammars/templ.json` | .templ |
| Terraform | `terraform` | `grammars/terraform.json` | .tf .tfvars |
| TeX | `tex` | `grammars/tex.json` | .texi |
| TOML | `toml` | `grammars/toml.json` | .toml Cargo.lock Pipfile |
| TSV | `tsv` | `grammars/tsv.json` | .tsv .tab |
| TSX | `tsx` | `grammars/tsx.json` | .tsx |
| Turtle | `turtle` | `grammars/turtle.json` | .ttl |
| Twig | `twig` | `grammars/twig.json` | .twig |
| TypeScript | `typescript` | `grammars/typescript.json` | .ts .mts .cts |
| TypeScript with Tags | `ts-tags` | `grammars/ts-tags.json` |  |
| TypeSpec | `typespec` | `grammars/typespec.json` | .tsp |
| Typst | `typst` | `grammars/typst.json` | .typ |
| V | `v` | `grammars/v.json` | .vv |
| Vala | `vala` | `grammars/vala.json` | .vala .vapi |
| Verilog | `verilog` | `grammars/verilog.json` | .v .vh |
| VHDL | `vhdl` | `grammars/vhdl.json` | .vhd .vhdl .vho |
| Vim Script | `viml` | `grammars/viml.json` | .vim .vimrc |
| Visual Basic | `vb` | `grammars/vb.json` | .vb .bas .vbs |
| Vue | `vue` | `grammars/vue.json` | .vue |
| Vue HTML | `vue-html` | `grammars/vue-html.json` |  |
| Vue Vine | `vue-vine` | `grammars/vue-vine.json` |  |
| vue-directives | `vue-directives` | `grammars/vue-directives.json` |  |
| vue-interpolations | `vue-interpolations` | `grammars/vue-interpolations.json` |  |
| vue-sfc-style-variable-injection | `vue-sfc-style-variable-injection` | `grammars/vue-sfc-style-variable-injection.json` |  |
| Vyper | `vyper` | `grammars/vyper.json` | .vy |
| WebAssembly | `wasm` | `grammars/wasm.json` | .wat .wast |
| WebAssembly Interface Types | `wit` | `grammars/wit.json` | .wit |
| Wenyan | `wenyan` | `grammars/wenyan.json` | .wy |
| WGSL | `wgsl` | `grammars/wgsl.json` | .wgsl |
| Wikitext | `wikitext` | `grammars/wikitext.json` | .wiki .mediawiki |
| Windows Registry Script | `reg` | `grammars/reg.json` | .reg |
| Wolfram | `wolfram` | `grammars/wolfram.json` | .wl .wls .nb |
| XML | `xml` | `grammars/xml.json` | .xml .xsd .xaml .svg .plist .csproj AndroidManifest.xml |
| XSL | `xsl` | `grammars/xsl.json` | .xsl .xslt |
| YAML | `yaml` | `grammars/yaml.json` | .yml .yaml .clang-format .clang-tidy .clang-format |
| ZenScript | `zenscript` | `grammars/zenscript.json` | .zs |
| Zig | `zig` | `grammars/zig.json` | .zig .zon |
