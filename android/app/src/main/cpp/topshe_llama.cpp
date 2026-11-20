// Thin JNI wrapper around llama.cpp: load a GGUF once, generate text from a prompt.
#include <jni.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "topshe-llama", __VA_ARGS__)

static llama_model *g_model = nullptr;
static std::mutex g_mu;

extern "C" JNIEXPORT jboolean JNICALL
Java_org_annay_topshe_NativeLlama_load(JNIEnv *env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_model) return JNI_TRUE;
    llama_backend_init();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    LOGI("model load %s", g_model ? "ok" : "FAILED");
    return g_model ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_annay_topshe_NativeLlama_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_annay_topshe_NativeLlama_generate(JNIEnv *env, jobject, jstring jprompt,
                                           jint maxTokens, jfloat temp, jint threads) {
    std::lock_guard<std::mutex> lk(g_mu);
    std::string out;
    if (g_model) {
        const char *p = env->GetStringUTFChars(jprompt, nullptr);
        std::string prompt(p);
        env->ReleaseStringUTFChars(jprompt, p);

        const llama_vocab *vocab = llama_model_get_vocab(g_model);
        int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, false, true);
        std::vector<llama_token> toks(std::max(n, 0));
        if (n > 0 && llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n, false, true) >= 0) {
            llama_context_params cp = llama_context_default_params();
            cp.n_ctx = (uint32_t) std::max(512, n + maxTokens + 16);
            cp.n_batch = cp.n_ctx;
            cp.n_threads = threads;
            cp.n_threads_batch = threads;
            llama_context *ctx = llama_init_from_model(g_model, cp);
            if (ctx) {
                llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
                if (temp <= 0.0f) {
                    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
                } else {
                    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
                    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
                    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
                    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
                }
                llama_token tok = 0;
                llama_batch batch = llama_batch_get_one(toks.data(), n);
                for (int i = 0; i < maxTokens; i++) {
                    if (llama_decode(ctx, batch) != 0) break;
                    tok = llama_sampler_sample(smpl, ctx, -1);
                    if (llama_vocab_is_eog(vocab, tok)) break;
                    char buf[256];
                    int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
                    if (len > 0) out.append(buf, len);
                    batch = llama_batch_get_one(&tok, 1);
                }
                llama_sampler_free(smpl);
                llama_free(ctx);
            }
        }
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), (const jbyte *) out.data());
    return arr;
}
