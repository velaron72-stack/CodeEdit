// JNI glue: app.codeedit.highlight.TmNative (see docs/ARCHITECTURE.md for the contract).
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "tm_registry.hpp"
#include "tm_session.hpp"

namespace {

using tmg::Registry;
using tmg::Session;

thread_local std::string g_lastError;

void setError(const std::string& e) { g_lastError = e; }

struct RegistryHandle {
    std::shared_ptr<Registry> reg;
};

/// Keeps the Java provider alive and releases the global ref on whatever thread drops the last reference.
struct ProviderRef {
    JavaVM* vm = nullptr;
    jobject obj = nullptr;
    jmethodID load = nullptr;
    ~ProviderRef() {
        if (!obj || !vm) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
            if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
            if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
#endif
            attached = true;
        }
        env->DeleteGlobalRef(obj);
        if (attached) vm->DetachCurrentThread();
    }
};

bool callProvider(const std::shared_ptr<ProviderRef>& p, const std::string& file, std::string& bytes) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (p->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if (p->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
#else
        if (p->vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return false;
#endif
        attached = true;
    }
    bool ok = false;
    jstring js = env->NewStringUTF(file.c_str());
    if (js) {
        jobject res = env->CallObjectMethod(p->obj, p->load, js);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        } else if (res) {
            jbyteArray arr = static_cast<jbyteArray>(res);
            jsize n = env->GetArrayLength(arr);
            bytes.resize(static_cast<size_t>(n));
            if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(&bytes[0]));
            ok = true;
        }
        if (res) env->DeleteLocalRef(res);
        env->DeleteLocalRef(js);
    }
    if (attached) p->vm->DetachCurrentThread();
    return ok;
}

std::string toStd(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

bool readBytes(JNIEnv* env, jbyteArray a, std::string& out) {
    if (!a) return false;
    jsize n = env->GetArrayLength(a);
    out.resize(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(&out[0]));
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_app_codeedit_highlight_TmNative_nativeVersion(JNIEnv* env, jclass) {
    std::string v = std::string("vscode-textmate 9.3.2 (C++ port) + Oniguruma ") + tmg::onigVersionString();
    return env->NewStringUTF(v.c_str());
}

JNIEXPORT jlong JNICALL Java_app_codeedit_highlight_TmNative_createRegistry(JNIEnv* env, jclass, jobject provider, jbyteArray manifest) {
    try {
        std::string json;
        if (!provider || !readBytes(env, manifest, json)) { setError("null provider or manifest"); return 0; }
        auto p = std::make_shared<ProviderRef>();
        env->GetJavaVM(&p->vm);
        jclass cls = env->GetObjectClass(provider);
        p->load = env->GetMethodID(cls, "loadGrammar", "(Ljava/lang/String;)[B");
        env->DeleteLocalRef(cls);
        if (!p->load) { env->ExceptionClear(); setError("GrammarProvider.loadGrammar not found"); return 0; }
        p->obj = env->NewGlobalRef(provider);
        auto reg = std::make_shared<Registry>([p](const std::string& file, std::string& bytes) { return callProvider(p, file, bytes); });
        std::string err;
        if (!reg->loadManifest(json.data(), json.size(), err)) { setError("manifest: " + err); return 0; }
        auto* h = new RegistryHandle{reg};
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        setError(e.what());
    } catch (...) {
        setError("unknown native error");
    }
    return 0;
}

JNIEXPORT void JNICALL Java_app_codeedit_highlight_TmNative_destroyRegistry(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<RegistryHandle*>(h);
}

JNIEXPORT jintArray JNICALL Java_app_codeedit_highlight_TmNative_setTheme(JNIEnv* env, jclass, jlong h, jbyteArray themeJson, jint defFg, jint defBg) {
    try {
        auto* rh = reinterpret_cast<RegistryHandle*>(h);
        std::string json;
        if (!rh || !readBytes(env, themeJson, json)) { setError("bad arguments"); return nullptr; }
        tmg::JValue doc;
        std::string err;
        if (!tmg::parseJson(json.data(), json.size(), doc, err)) { setError("theme: " + err); return nullptr; }
        std::vector<uint32_t> table = rh->reg->setTheme(doc, static_cast<uint32_t>(defFg), static_cast<uint32_t>(defBg), err);
        if (table.empty()) { setError("theme: " + err); return nullptr; }
        jintArray out = env->NewIntArray(static_cast<jsize>(table.size()));
        if (!out) return nullptr;
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(table.size()), reinterpret_cast<const jint*>(table.data()));
        return out;
    } catch (const std::exception& e) {
        setError(e.what());
    } catch (...) {
        setError("unknown native error");
    }
    return nullptr;
}

JNIEXPORT jlong JNICALL Java_app_codeedit_highlight_TmNative_createSession(JNIEnv* env, jclass, jlong h, jstring rootScope, jint maxLineChars, jint stride, jint maxCheckpoints) {
    try {
        auto* rh = reinterpret_cast<RegistryHandle*>(h);
        if (!rh) { setError("registry destroyed"); return 0; }
        std::string scope = toStd(env, rootScope);
        auto raw = rh->reg->lookup(scope);
        if (!raw) {
            std::string e = rh->reg->lastError();
            setError(e.empty() ? "unknown grammar scope: " + scope : e);
            return 0;
        }
        auto s = std::make_unique<Session>(rh->reg, raw, maxLineChars, stride, maxCheckpoints);
        std::string err;
        if (!s->prepare(err)) { setError(err); return 0; }
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::exception& e) {
        setError(e.what());
    } catch (...) {
        setError("unknown native error");
    }
    return 0;
}

JNIEXPORT void JNICALL Java_app_codeedit_highlight_TmNative_destroySession(JNIEnv*, jclass, jlong s) {
    delete reinterpret_cast<Session*>(s);
}

JNIEXPORT jstring JNICALL Java_app_codeedit_highlight_TmNative_lastError(JNIEnv* env, jclass) {
    if (g_lastError.empty()) return nullptr;
    return env->NewStringUTF(g_lastError.c_str());
}

JNIEXPORT void JNICALL Java_app_codeedit_highlight_TmNative_invalidateFrom(JNIEnv*, jclass, jlong s, jint line) {
    if (s) reinterpret_cast<Session*>(s)->invalidateFrom(line);
}

JNIEXPORT jint JNICALL Java_app_codeedit_highlight_TmNative_nearestCheckpoint(JNIEnv*, jclass, jlong s, jint line) {
    return s ? reinterpret_cast<Session*>(s)->nearestCheckpoint(line) : 0;
}

JNIEXPORT jint JNICALL Java_app_codeedit_highlight_TmNative_maxCheckpoint(JNIEnv*, jclass, jlong s) {
    return s ? reinterpret_cast<Session*>(s)->maxCheckpoint() : 0;
}

JNIEXPORT jint JNICALL Java_app_codeedit_highlight_TmNative_checkpointCount(JNIEnv*, jclass, jlong s) {
    return s ? reinterpret_cast<Session*>(s)->checkpointCount() : 0;
}

JNIEXPORT jintArray JNICALL Java_app_codeedit_highlight_TmNative_tokenize(JNIEnv* env, jclass, jlong s, jint startLine, jcharArray chars, jintArray offsets,
                                                                          jint lineCount, jint emitFrom, jboolean cold, jboolean record, jint timeLimitMs) {
    try {
        auto* sess = reinterpret_cast<Session*>(s);
        if (!sess || !chars || !offsets || lineCount < 0) { setError("bad arguments"); return nullptr; }
        jsize nChars = env->GetArrayLength(chars);
        if (env->GetArrayLength(offsets) < lineCount + 1) { setError("offsets too short"); return nullptr; }
        std::vector<jchar> text(static_cast<size_t>(nChars));
        if (nChars > 0) env->GetCharArrayRegion(chars, 0, nChars, text.data());
        std::vector<jint> offs(static_cast<size_t>(lineCount) + 1);
        env->GetIntArrayRegion(offsets, 0, lineCount + 1, offs.data());
        std::vector<int32_t> out;
        std::string err;
        bool ok = sess->tokenize(startLine, reinterpret_cast<const char16_t*>(text.data()), nChars, reinterpret_cast<const int*>(offs.data()), lineCount, emitFrom,
                                 cold == JNI_TRUE, record == JNI_TRUE, timeLimitMs, out, err);
        if (!ok) { setError(err); return nullptr; }
        jintArray res = env->NewIntArray(static_cast<jsize>(out.size()));
        if (!res) return nullptr;
        if (!out.empty()) env->SetIntArrayRegion(res, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jint*>(out.data()));
        return res;
    } catch (const std::exception& e) {
        setError(e.what());
    } catch (...) {
        setError("unknown native error");
    }
    return nullptr;
}

}  // extern "C"
