#include <jni.h>
#include <espeak-ng/speak_lib.h>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex guard;
bool initialized = false;
std::vector<short> samples;
int collect(short* wave, int count, espeak_EVENT*) {
  if (wave && count > 0) samples.insert(samples.end(), wave, wave + count);
  return samples.size() > 22050 * 120 ? 1 : 0;
}
std::string bytes(JNIEnv* env, jbyteArray input) {
  std::string value(env->GetArrayLength(input), '\0');
  env->GetByteArrayRegion(input, 0, value.size(), reinterpret_cast<jbyte*>(value.data()));
  return value;
}
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_studio_tingye_reader_ClassicSpeech_generate(JNIEnv* env, jclass, jbyteArray folder,
                                                jbyteArray text, jbyteArray language) {
  std::lock_guard<std::mutex> lock(guard);
  if (!initialized) {
    auto path = bytes(env, folder);
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path.c_str(), 0);
    if (rate != 22050) return nullptr;
    espeak_SetSynthCallback(collect);
    initialized = true;
  }
  auto voice = bytes(env, language);
  if (espeak_SetVoiceByName(voice.c_str()) != EE_OK) return nullptr;
  espeak_SetParameter(espeakRATE, voice == "cmn" ? 165 : 155, 0);
  auto phrase = bytes(env, text);
  samples.clear();
  if (espeak_Synth(phrase.c_str(), phrase.size()+1, 0, POS_CHARACTER, 0,
                   espeakCHARS_UTF8 | espeakENDPAUSE, nullptr, nullptr) != EE_OK) return nullptr;
  jshortArray result = env->NewShortArray(samples.size());
  if (result) env->SetShortArrayRegion(result, 0, samples.size(), samples.data());
  samples.clear();
  return result;
}
