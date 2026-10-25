/* Minimal libretro frontend: runs the SNES core, draws its frames straight into the
 * Android Surface (no OpenGL), returns audio to Java and takes the pad state from Java. */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "libretro.h"

#define TAG "RetroSNES"
#define JNI(name) Java_com_example_retrosnes_GameActivity_##name
#define AUDIO_MAX (4096 * 2)

static ANativeWindow *window;
static int win_w, win_h;
static char save_dir[512];
static int pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static unsigned pad;
static int16_t audio[AUDIO_MAX];
static int audio_len;               /* in int16 values (stereo pairs * 2) */
static double core_fps;
static int64_t wait_ns;             /* time spent waiting for a free screen buffer (vsync) */

static int64_t now_ns(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec;
}

static void log_cb(enum retro_log_level level, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    __android_log_vprint(level >= RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, TAG, fmt, ap);
    va_end(ap);
}

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((struct retro_log_callback *)data)->log = log_cb; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        pixfmt = *(const enum retro_pixel_format *)data;
        return pixfmt == RETRO_PIXEL_FORMAT_RGB565;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = save_dir; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = false; return true;
    default:
        return false;               /* everything else: core uses its defaults */
    }
}

static void *rom_data;

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !window) return;   /* NULL = duplicate frame, keep the last one */
    /* Integer upscale so the TV's scaler keeps pixels sharp: 4x for 256x224,
     * less in the SNES hi-res (512 wide) and interlaced (448 tall) modes */
    unsigned sx = w > 256 ? 2 : 4, sy = h > 256 ? 2 : 4;
    if (win_w != (int)(w * sx) || win_h != (int)(h * sy)) {
        win_w = w * sx; win_h = h * sy;
        ANativeWindow_setBuffersGeometry(window, win_w, win_h, WINDOW_FORMAT_RGB_565);
    }
    ANativeWindow_Buffer buf;
    int64_t t = now_ns();
    int locked = ANativeWindow_lock(window, &buf, NULL);
    wait_ns += now_ns() - t;
    if (locked != 0) return;
    for (unsigned y = 0; y < h; y++) {
        const uint16_t *src = (const uint16_t *)((const uint8_t *)data + y * pitch);
        uint16_t *dst = (uint16_t *)buf.bits + (size_t)y * sy * buf.stride;
        if (sx == 4) for (unsigned x = 0; x < w; x++) { uint16_t *d = dst + x * 4; d[0] = d[1] = d[2] = d[3] = src[x]; }
        else for (unsigned x = 0; x < w; x++) dst[x * 2] = dst[x * 2 + 1] = src[x];
        for (unsigned r = 1; r < sy; r++)
            memcpy(dst + r * buf.stride, dst, w * sx * 2);
    }
    ANativeWindow_unlockAndPost(window);
}

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    size_t n = frames * 2;
    if (audio_len + n > AUDIO_MAX) n = AUDIO_MAX - audio_len;
    memcpy(audio + audio_len, data, n * 2);
    audio_len += n;
    return frames;
}

static void audio_cb(int16_t l, int16_t r) {
    if (audio_len + 2 <= AUDIO_MAX) { audio[audio_len++] = l; audio[audio_len++] = r; }
}

static void input_poll_cb(void) {}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return pad;
    return (pad >> id) & 1;
}

/* Returns the audio sample rate, or 0 if the game didn't load */
JNIEXPORT jint JNICALL
JNI(nativeLoad)(JNIEnv *env, jclass c, jstring jrom, jstring jdir) {
    const char *rom = (*env)->GetStringUTFChars(env, jrom, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    snprintf(save_dir, sizeof save_dir, "%s", dir);
    retro_set_environment(env_cb);
    retro_set_video_refresh(video_cb);
    retro_set_audio_sample(audio_cb);
    retro_set_audio_sample_batch(audio_batch_cb);
    retro_set_input_poll(input_poll_cb);
    retro_set_input_state(input_state_cb);
    retro_init();
    /* This core loads from memory, so read the whole ROM in (and still pass the path) */
    long size = 0;
    FILE *f = fopen(rom, "rb");
    if (f) {
        fseek(f, 0, SEEK_END); size = ftell(f); fseek(f, 0, SEEK_SET);
        rom_data = malloc(size);
        if (fread(rom_data, 1, size, f) != (size_t)size) size = 0;
        fclose(f);
    }
    struct retro_game_info info = { rom, rom_data, (size_t)size, NULL };
    bool ok = size > 0 && retro_load_game(&info);
    (*env)->ReleaseStringUTFChars(env, jrom, rom);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    if (!ok) { retro_deinit(); free(rom_data); rom_data = NULL; return 0; }
    struct retro_system_av_info av;
    retro_get_system_av_info(&av);
    __android_log_print(ANDROID_LOG_INFO, TAG, "loaded: %ux%u %.3f fps, %.0f Hz",
            av.geometry.base_width, av.geometry.base_height, av.timing.fps, av.timing.sample_rate);
    core_fps = av.timing.fps;
    return (jint)av.timing.sample_rate;
}

JNIEXPORT jdouble JNICALL
JNI(nativeFps)(JNIEnv *env, jclass c) { return core_fps; }

/* Nanoseconds spent waiting on the screen since the last call (to separate it from emulation time) */
JNIEXPORT jlong JNICALL
JNI(nativeWait)(JNIEnv *env, jclass c) {
    int64_t w = wait_ns; wait_ns = 0; return w;
}

JNIEXPORT void JNICALL
JNI(nativeSurface)(JNIEnv *env, jclass c, jobject surface) {
    if (window) ANativeWindow_release(window);
    window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    win_w = win_h = 0;
}

/* Runs one frame; copies its audio into out and returns how many int16 values it wrote */
JNIEXPORT jint JNICALL
JNI(nativeFrame)(JNIEnv *env, jclass c, jint buttons, jshortArray out) {
    pad = (unsigned)buttons;
    audio_len = 0;
    retro_run();
    jsize cap = (*env)->GetArrayLength(env, out);
    int n = audio_len < cap ? audio_len : cap;
    (*env)->SetShortArrayRegion(env, out, 0, n, audio);
    return n;
}

JNIEXPORT void JNICALL
JNI(nativeUnload)(JNIEnv *env, jclass c) {
    retro_unload_game();
    retro_deinit();
    free(rom_data);
    rom_data = NULL;
}
