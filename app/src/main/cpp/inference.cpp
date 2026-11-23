#include <jni.h>
#include <llama.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex engine_lock;
std::once_flag initialized;
std::atomic<bool> interrupted{false};
llama_model *model = nullptr;

bool stop_requested(void *) {
    return interrupted.load(std::memory_order_relaxed);
}

void log_message(enum ggml_log_level level, const char *message, void *) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, "SlovoLocal", message);
    }
}

std::string bytes_from(JNIEnv *env, jbyteArray array) {
    std::string bytes(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<jbyte *>(bytes.data()));
    return bytes;
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_studio_tingye_reader_LocalLanguageModel_infer(JNIEnv *env, jclass, jbyteArray asset,
                                                   jbyteArray input, jint threads, jint limit) {
    std::lock_guard<std::mutex> guard(engine_lock);
    interrupted.store(false, std::memory_order_relaxed);
    std::call_once(initialized, [] {
        llama_log_set(log_message, nullptr);
        llama_backend_init();
    });
    if (!model) {
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.use_mmap = true;
        model = llama_model_load_from_file(bytes_from(env, asset).c_str(), params);
    }
    if (!model) return nullptr;
    std::string prompt = bytes_from(env, input);
    const llama_vocab *vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(1536);
    int count = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()),
                               tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    if (count <= 0) return nullptr;
    llama_context_params parameters = llama_context_default_params();
    if (count + limit + 8 > 1536) return nullptr;
    parameters.n_ctx = 1536;
    parameters.n_batch = 1536;
    parameters.n_ubatch = 96;
    parameters.n_threads = threads;
    parameters.n_threads_batch = threads;
    parameters.abort_callback = stop_requested;
    llama_context *context = llama_init_from_model(model, parameters);
    if (!context) return nullptr;
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(128, 1.08f, 0.0f, 0.2f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.4f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    std::string answer;
    int status = llama_decode(context, llama_batch_get_one(tokens.data(), count));
    for (int step = 0; status == 0 && step < limit && !stop_requested(nullptr); ++step) {
        llama_token next = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        char piece[512];
        int length = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (length > 0) answer.append(piece, length);
        status = llama_decode(context, llama_batch_get_one(&next, 1));
    }
    llama_sampler_free(sampler);
    llama_free(context);
    if (answer.empty() || stop_requested(nullptr)) return nullptr;
    jbyteArray response = env->NewByteArray(static_cast<jsize>(answer.size()));
    if (response) env->SetByteArrayRegion(response, 0, static_cast<jsize>(answer.size()),
                                        reinterpret_cast<const jbyte *>(answer.data()));
    return response;
}

extern "C" JNIEXPORT void JNICALL
Java_studio_tingye_reader_LocalLanguageModel_interrupt(JNIEnv *, jclass) {
    interrupted.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_studio_tingye_reader_LocalLanguageModel_release(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(engine_lock);
    if (model) llama_model_free(model);
    model = nullptr;
}

