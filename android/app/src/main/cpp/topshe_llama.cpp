// JNI bridge to llama.cpp for Topshe.
//
// Speed trick - prompt caching: every request is "fixed instructions + your words". The fixed part
// (system prompt + memory) is processed once, its state is saved, and later requests restore that
// state and only process your few new words. This works for Qwen 3.5's hybrid (attention +
// recurrent) layers too, because we save/restore the whole sequence state, not just the KV cache.
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "topshe-llama", __VA_ARGS__)

namespace {

struct Slot {
    std::string prefix;
    std::vector<uint8_t> state;
    long last_used = 0;
};

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int threads = 4;
    std::vector<Slot> slots;   // a few cached prefixes (command prompt, chat prompt, ...)
    long clock = 0;
};

std::mutex g_mu;   // one generation at a time (phone CPU)
std::atomic<bool> g_abort{false};   // set by the kill switch / Stop button: ends the current answer now
std::string g_stats;                // timings of the last answer (for the activity log)

bool abort_cb(void *) { return g_abort.load(); }   // checked by llama.cpp during every computation

double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
const int N_CTX = 4096;
const int N_BATCH = 512;
const size_t MAX_SLOTS = 3;

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> toks(std::max(n, 0));
    if (n > 0 && llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, false, true) < 0) toks.clear();
    return toks;
}

bool decode_all(llama_context *ctx, std::vector<llama_token> &toks) {
    for (size_t i = 0; i < toks.size(); i += N_BATCH) {
        int n = (int) std::min<size_t>(N_BATCH, toks.size() - i);
        if (g_abort.load() || llama_decode(ctx, llama_batch_get_one(toks.data() + i, n)) != 0) return false;
    }
    return true;
}

bool ensure_ctx(Engine *e, int threads) {
    if (!e->ctx) {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = N_CTX;
        cp.n_batch = N_BATCH;
        cp.n_ubatch = N_BATCH;
        cp.n_seq_max = 1;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        e->ctx = llama_init_from_model(e->model, cp);
        if (e->ctx) llama_set_abort_callback(e->ctx, abort_cb, nullptr);
        e->threads = threads;
        e->slots.clear();
    } else if (threads != e->threads) {
        llama_set_n_threads(e->ctx, threads, threads);
        e->threads = threads;
    }
    return e->ctx != nullptr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_annay_topshe_NativeLlama_load(JNIEnv *env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> lk(g_mu);
    static bool backend = false;
    if (!backend) { llama_backend_init(); backend = true; }
    std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    llama_model *m = llama_model_load_from_file(path.c_str(), mp);
    LOGI("model load %s: %s", path.c_str(), m ? "ok" : "FAILED");
    if (!m) return 0;
    auto *e = new Engine();
    e->model = m;
    return (jlong) e;
}

extern "C" JNIEXPORT void JNICALL
Java_org_annay_topshe_NativeLlama_unload(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lk(g_mu);
    auto *e = (Engine *) handle;
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

// prefix: fixed part (cached). suffix: the new part (your words + the reply opener).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_annay_topshe_NativeLlama_generate(JNIEnv *env, jobject, jlong handle, jstring jprefix, jstring jsuffix,
                                           jint maxTokens, jfloat temp, jint threads) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_abort = false;
    std::string out;
    double t0 = now_ms(), t_prefix = 0, t_suffix = 0, t_gen = 0;
    int n_prefix = 0, n_suffix = 0, n_gen = 0;
    bool cached = false;
    auto *e = (Engine *) handle;
    if (e && ensure_ctx(e, threads)) {
        const llama_vocab *vocab = llama_model_get_vocab(e->model);
        std::string prefix = jstr(env, jprefix);
        std::string suffix = jstr(env, jsuffix);
        auto suffix_toks = tokenize(vocab, suffix);
        llama_memory_t mem = llama_get_memory(e->ctx);
        bool ready = false;

        // 1) Restore the cached prefix if we have it
        for (auto &s : e->slots) {
            if (s.prefix == prefix) {
                llama_memory_clear(mem, true);
                ready = llama_state_seq_set_data(e->ctx, s.state.data(), s.state.size(), 0) > 0;
                cached = ready;
                s.last_used = ++e->clock;
                break;
            }
        }
        // 2) Otherwise process it once and save it for next time
        if (!ready) {
            llama_memory_clear(mem, true);
            auto prefix_toks = tokenize(vocab, prefix);
            n_prefix = (int) prefix_toks.size();
            double tp = now_ms();
            bool ok = (int) (prefix_toks.size() + suffix_toks.size()) + maxTokens < N_CTX && decode_all(e->ctx, prefix_toks);
            t_prefix = now_ms() - tp;
            if (ok) {
                Slot s;
                s.prefix = prefix;
                s.state.resize(llama_state_seq_get_size(e->ctx, 0));
                size_t got = llama_state_seq_get_data(e->ctx, s.state.data(), s.state.size(), 0);
                if (got > 0) {
                    s.state.resize(got);
                    s.last_used = ++e->clock;
                    if (e->slots.size() >= MAX_SLOTS) {
                        auto lru = std::min_element(e->slots.begin(), e->slots.end(),
                                                    [](const Slot &a, const Slot &b) { return a.last_used < b.last_used; });
                        e->slots.erase(lru);
                    }
                    e->slots.push_back(std::move(s));
                }
                ready = true;
            }
        }
        // 3) Your words, then generate the reply
        n_suffix = (int) suffix_toks.size();
        double ts = now_ms();
        bool suffix_ok = ready && llama_memory_seq_pos_max(mem, 0) + (int) suffix_toks.size() + maxTokens < N_CTX &&
            decode_all(e->ctx, suffix_toks);
        t_suffix = now_ms() - ts;
        if (suffix_ok) {
            double tg = now_ms();
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
            for (int i = 0; i < maxTokens && !g_abort.load(); i++) {
                tok = llama_sampler_sample(smpl, e->ctx, -1);
                if (llama_vocab_is_eog(vocab, tok)) break;
                char buf[256];
                int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
                if (len > 0) out.append(buf, len);
                n_gen++;
                if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
            }
            llama_sampler_free(smpl);
            t_gen = now_ms() - tg;
        }
        if (g_abort.load()) { llama_memory_clear(mem, true); out.clear(); }   // stopped: drop the half answer
    }
    char buf[256];
    snprintf(buf, sizeof(buf), "%s%s threads %d | instructions %d tok %.1fs%s | your words %d tok %.1fs | reply %d tok %.1fs (%.1f tok/s) | total %.1fs",
             g_abort.load() ? "STOPPED " : "", "brain", e ? e->threads : 0, n_prefix, t_prefix / 1000, cached ? " (cached)" : "",
             n_suffix, t_suffix / 1000, n_gen, t_gen / 1000, t_gen > 0 ? n_gen * 1000.0 / t_gen : 0.0, (now_ms() - t0) / 1000);
    g_stats = buf;
    LOGI("%s", buf);
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), (const jbyte *) out.data());
    return arr;
}

// Kill switch / Stop button: ends the current answer within a moment (no lock: callable any time).
extern "C" JNIEXPORT void JNICALL
Java_org_annay_topshe_NativeLlama_abort(JNIEnv *, jobject) { g_abort = true; }

extern "C" JNIEXPORT jstring JNICALL
Java_org_annay_topshe_NativeLlama_stats(JNIEnv *env, jobject) { return env->NewStringUTF(g_stats.c_str()); }
