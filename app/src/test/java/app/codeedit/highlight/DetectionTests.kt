package app.codeedit.highlight

import org.junit.Assert.*
import org.junit.Test

class ContentSnifferTests {
    private fun g(s: String): String? = ContentSniffer.guess(s.trimIndent().lines())

    @Test fun exteraGramPluginIsPython() {
        assertEquals("python", g("""
            import gzip
            import hashlib
            import json
            from base_plugin import BasePlugin, HookResult, HookStrategy
            from android_utils import run_on_ui_thread, log

            __id__ = "plugin_guard"
            __name__ = "Plugin Guard"
            __version__ = "1.0.0"

            class PluginGuard(BasePlugin):
                def on_plugin_load(self):
                    self.add_on_send_message_hook()

                def on_send_message_hook(self, account, params):
                    if not self.get_setting("enabled", True):
                        return HookResult()
                    return HookResult(strategy=HookStrategy.DEFAULT)
        """))
    }

    @Test fun commonLanguages() {
        assertEquals("python", g("""
            import os
            import sys

            def main():
                print(os.getcwd())

            if __name__ == "__main__":
                main()
        """))
        assertEquals("javascript", g("""
            const express = require('express');
            const app = express();
            app.get('/', (req, res) => {
              console.log('hit');
              res.send('ok');
            });
            module.exports = app;
        """))
        assertEquals("typescript", g("""
            export interface User {
              id: number;
              name: string;
            }
            export const load = async (id: number): Promise<User> => {
              const r = await fetch(`/api/${'$'}{id}`);
              return r.json() as User;
            };
        """))
        assertEquals("shellscript", g("""
            set -e
            export PATH="${'$'}HOME/bin:${'$'}PATH"
            echo "building"
            cd "${'$'}1"
            if [ -f build.gradle ]; then
              gradle assemble
            fi
        """))
        assertEquals("java", g("""
            package com.example;
            import java.util.List;
            public class Main {
                @Override
                public static void main(String[] args) {
                    System.out.println("hi");
                }
            }
        """))
        assertEquals("kotlin", g("""
            package app.demo

            import android.os.Bundle

            data class P(val x: Int)

            fun main() {
                val p = P(1)
                println(p)
            }
        """))
        assertEquals("csharp", g("""
            using System;
            namespace Demo {
                public class Program {
                    static void Main() { Console.WriteLine("hi"); }
                }
            }
        """))
        assertEquals("lua", g("""
            local function add(a, b)
              if a ~= nil then
                return a + b
              end
            end
        """))
        assertEquals("css", g("""
            .box {
              color: red;
              margin: 0 auto;
            }
            body > p {
              font-size: 12px;
            }
        """))
        assertEquals("sql", g("""
            SELECT id, name FROM users WHERE id = 1;
            INSERT INTO users (id) VALUES (2);
        """))
        assertEquals("c", g("""
            #include <stdio.h>
            int main(void) {
                printf("hi\n");
                return 0;
            }
        """))
        assertEquals("cpp", g("""
            #include <iostream>
            #include <vector>
            int main() {
                std::vector<int> v;
                std::cout << v.size();
            }
        """))
    }

    @Test fun structuredFormats() {
        assertEquals("json", g("""{"a": 1, "b": [1, 2], "c": {"d": "e"}}"""))
        assertEquals("json", g("[\n  {\"id\": 1, \"name\": \"x\"}\n]"))
        assertEquals("xml", g("""<?xml version="1.0" encoding="utf-8"?><manifest package="a.b"/>"""))
        assertEquals("xml", g("<root>\n  <item a=\"1\"/>\n  <item a=\"2\"/>\n</root>"))
        assertEquals("html", g("<html>\n<head><title>x</title></head>\n<body><div>hi</div></body>\n</html>"))
    }

    @Test fun proseLogsAndTinySnippetsStayPlain() {
        assertNull(ContentSniffer.guess(emptyList()))
        assertNull(g("   \n\n  "))
        assertNull(g("hello"))
        assertNull(g("Hello world. This is a note about my day.\nI went to the shop and bought bread.\n"))
        assertNull(g("2026-11-02 10:00:01 INFO Starting server\n2026-11-02 10:00:02 WARN slow disk\n2026-11-02 10:00:03 INFO ready"))
        assertNull(g("a,b,c\n1,2,3\n4,5,6"))
        assertNull(g("# Title\n\nSome text: with a colon\n* item one\n* item two\n"))
        assertNull("one weak cue is not enough", g("import os"))
        assertNull("a lone JSON-looking brace without keys", g("{ hello }"))
    }

    @Test fun typeScriptDeclarationsWithoutAnyJsCues() {
        assertEquals("typescript", g("""
            export interface Options {
              timeout?: number;
              name: string;
            }
            export type Handler = (req: Request) => void;
            declare function create(opts: Options): Handler;
        """))
    }

    @Test fun documentsThatContainCodeAreNotSniffedAsThatCode() {
        assertNull(g("""
            # Title

            ```js
            const a = require('a');
            console.log(a);
            ```
        """))
        assertNull(g("""
            name: Build
            on:
              push:
                branches: [ main ]
            jobs:
              build:
                steps:
                  - run: |
                      echo hi
                      export A=1
                      cd x
        """))
        assertNull(g("---\nrules:\n  - id: a\n    message: x\n  - id: b\n    message: y\n"))
    }

    @Test fun lookAlikesDoNotCollectPoints() {
        // bash `local x=1` is not Lua
        assertNotEquals("lua", g("f() {\n  local x=1\n  local y=2\n  echo ${'$'}x\n}\nf\n"))
        // a Lua call with a table argument is not a CSS selector
        assertEquals("lua", g("""
            local x = 1
            declare {
              key = "a",
            }
            local function f()
              return nil
            end
        """))
        // C++ qualified names and switch labels are not CSS declarations
        assertEquals("cpp", g("""
            #include <string>
            #include <mutex>
            namespace tmg {
              std::string err;
              std::lock_guard<std::mutex> lk(mu_);
            }
        """))
        assertNotEquals("css", g("switch (x) {\n  default: break;\n  case 1: y = 2;\n}\n"))
        // keyword-led braces are not selectors
        assertNotEquals("css", g("class A {\n}\nstruct B {\n}\nelse {\n}\n"))
    }

    @Test fun neverThrowsOnHostileInput() {
        val noisy = "{".repeat(400)
        val lines = listOf(noisy, "a:".repeat(200), "(".repeat(400), "x = ".repeat(100), "\u0000\uFFFF".repeat(100), "\t".repeat(400) + "{")
        ContentSniffer.guess(lines)          // must simply return
        ContentSniffer.guess(List(80) { "a b ".repeat(100) })
    }

    @Test fun onlyTheFirstLinesAreLookedAt() {
        val many = ArrayList<String>()
        repeat(ContentSniffer.MAX_LINES * 5) { many += "plain words $it" }
        many += listOf("import os", "import sys", "def f():", "    return 1", "class A:", "    pass")
        assertNull(ContentSniffer.guess(many.take(ContentSniffer.MAX_LINES)))     // the caller passes only MAX_LINES lines
        assertEquals("python", ContentSniffer.guess(many.takeLast(6)))
    }

    @Test fun veryLongLinesDoNotHangOrMisfire() {
        val long = "x".repeat(200_000)
        val t0 = System.nanoTime()
        assertNull(ContentSniffer.guess(listOf(long, long, long)))
        assertTrue("sniffing must be fast", (System.nanoTime() - t0) / 1_000_000 < 2000)
    }
}

class LanguageAssocTests {
    @Test fun keyOfNames() {
        assertEquals(".plugin", LanguageAssoc.keyOf("a.plugin"))
        assertEquals(".plugin", LanguageAssoc.keyOf("dir/Sub/A.PLUGIN"))
        assertEquals(".gitignore", LanguageAssoc.keyOf(".gitignore"))
        assertEquals(".gz", LanguageAssoc.keyOf("archive.tar.gz"))
        assertNull(LanguageAssoc.keyOf("README"))
        assertNull(LanguageAssoc.keyOf("trailing."))
        assertNull(LanguageAssoc.keyOf(null))
        assertNull(LanguageAssoc.keyOf("document:1234"))
    }

    @Test fun putGetRemove() {
        var j = LanguageAssoc.put(null, ".plugin", "python")
        assertEquals("python", LanguageAssoc.get(j, ".plugin"))
        assertEquals("python", LanguageAssoc.get(j, ".PLUGIN"))
        j = LanguageAssoc.put(j, ".foo", "json")
        j = LanguageAssoc.put(j, ".plugin", "lua")                       // replaces
        assertEquals("lua", LanguageAssoc.get(j, ".plugin"))
        assertEquals("json", LanguageAssoc.get(j, ".foo"))
        j = LanguageAssoc.put(j, ".plugin", null)                        // removes
        assertNull(LanguageAssoc.get(j, ".plugin"))
        assertEquals(setOf(".foo"), LanguageAssoc.parse(j).keys)
        assertTrue(LanguageAssoc.parse(LanguageAssoc.put(j, ".foo", null)).isEmpty())
    }

    @Test fun junkInputIsIgnored() {
        assertTrue(LanguageAssoc.parse(null).isEmpty())
        assertTrue(LanguageAssoc.parse("").isEmpty())
        assertTrue(LanguageAssoc.parse("not json").isEmpty())
        assertTrue(LanguageAssoc.parse("[1,2]").isEmpty())
        assertEquals(mapOf(".ok" to "python"), LanguageAssoc.parse("""{"ok": "x", ".ok": "python", ".num": 5, ".empty": ""}"""))
        assertEquals("python", LanguageAssoc.get(LanguageAssoc.put("garbage", ".a", "python"), ".a"))
    }

    @Test fun sizeStaysWithinTheSettingsLimit() {
        var j: String? = null
        for (i in 0 until 200) j = LanguageAssoc.put(j, ".ext$i", "language-with-a-rather-long-identifier-$i")
        assertTrue("json ${j!!.length} chars", j.length <= 1900)
        val m = LanguageAssoc.parse(j)
        assertTrue(m.size <= 60)
        assertTrue("the newest entry survives", ".ext199" in m)
        assertTrue("the oldest entries are dropped", ".ext0" !in m)
    }
}
