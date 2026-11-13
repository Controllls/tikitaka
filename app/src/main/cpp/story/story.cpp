#include <jni.h>
#include <llama.h>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::once_flag backend;
std::string bytes(JNIEnv *env, jbyteArray data) {
    std::string value(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
struct Control {
    JavaVM *vm;
    jobject callback;
    jmethodID method;
    std::chrono::steady_clock::time_point end;
};
bool abort_generation(void *opaque) {
    auto &control = *static_cast<Control *>(opaque);
    if (std::chrono::steady_clock::now() >= control.end) return true;
    JNIEnv *env = nullptr;
    bool attached = control.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached && control.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
    bool stop = env->CallBooleanMethod(control.callback, control.method);
    if (env->ExceptionCheck()) { env->ExceptionClear(); stop = true; }
    if (attached) control.vm->DetachCurrentThread();
    return stop;
}
void quiet_log(ggml_log_level, const char *, void *) {} // Never log private dialogue.
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_hd_1project_tiki_StoryNative_generate(JNIEnv *env, jobject, jbyteArray pathBytes,
    jbyteArray promptBytes, jbyteArray grammarBytes, jobject cancelled) {
    try {
        std::call_once(backend, [] { llama_log_set(quiet_log, nullptr); llama_backend_init(); });
        const auto path = bytes(env, pathBytes), prompt = bytes(env, promptBytes), grammar = bytes(env, grammarBytes);
        JavaVM *vm; env->GetJavaVM(&vm);
        const auto method = env->GetMethodID(env->GetObjectClass(cancelled), "shouldAbort", "()Z");
        if (!method) return nullptr;
        // The synchronous call keeps the caller's local reference alive throughout inference.
        const auto ref = env->NewGlobalRef(cancelled);
        struct Reference { JNIEnv *env; jobject ref; ~Reference() { env->DeleteGlobalRef(ref); } } reference{env, ref};
        Control control{vm, ref, method, std::chrono::steady_clock::now() + std::chrono::seconds(240)};
        auto mp = llama_model_default_params(); mp.n_gpu_layers = 0; mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback = [](float, void *data) { return !abort_generation(data); };
        mp.progress_callback_user_data = &control;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error("Story model could not load or was interrupted");
        const auto vocab = llama_model_get_vocab(model.get());
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count < 1 || count > 1700) throw std::runtime_error("Story dialogue exceeds context budget");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
            throw std::runtime_error("Story tokenizer failed");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 128; cp.n_ubatch = 128;
        cp.n_threads = 2; cp.n_threads_batch = 2;
        cp.abort_callback = abort_generation; cp.abort_callback_data = &control;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model.get(), cp), llama_free);
        if (!ctx) throw std::runtime_error("Story context could not allocate memory");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto constraint = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!constraint) throw std::runtime_error("Story grammar invalid");
        llama_sampler_chain_add(sampler.get(), constraint);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
        int position = 0;
        auto decode = [&](const llama_token *input, int n) {
            if (abort_generation(&control)) throw std::runtime_error("Story inference interrupted or timed out");
            llama_batch_ext_clear(batch.get());
            for (int i = 0; i < n; ++i) {
                int index = llama_batch_ext_add_token(batch.get(), 0, input[i]);
                if (index < 0) throw std::runtime_error("Story batch rejected");
                llama_pos pos = position++;
                llama_batch_ext_set_pos(batch.get(), index, &pos);
            }
            llama_batch_ext_set_output_logits(batch.get(), n-1, true);
            if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()))
                throw std::runtime_error("Story inference interrupted or failed");
        };
        for (int offset = 0; offset < count; offset += 128) decode(tokens.data()+offset, std::min(128, count-offset));
        std::string output;
        bool completed = false;
        for (int i = 0; i < 300; ++i) {
            auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { completed = true; break; }
            char piece[1024]; int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (n < 0) throw std::runtime_error("Story token too large");
            output.append(piece, n); decode(&token, 1);
        }
        if (!completed) throw std::runtime_error("Story response exceeded token budget");
        auto result = env->NewByteArray(output.size());
        env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const std::exception &e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}
