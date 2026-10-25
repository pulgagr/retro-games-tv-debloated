/* Minimal libretro frontend. Loads an emulator core library at runtime (one per console), runs it,
 * draws its frames straight into the Android Surface (no OpenGL), returns audio to Java, takes the
 * pad state from Java, and saves/loads states and battery saves. */
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "libretro.h"

#define TAG "Retro"
#define JNI(name) Java_com_example_retro_GameActivity_##name
#define AUDIO_MAX (4096 * 2)
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* The core's libretro functions, looked up with dlsym */
static struct {
    void *lib;
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*init)(void);
    void (*deinit)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void (*run)(void);
    void (*reset)(void);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
} core;

static ANativeWindow *window;
static int win_w, win_h;
static char sys_dir[512];
static int pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static unsigned pad;
static int16_t audio[AUDIO_MAX];
static int audio_len;               /* in int16 values (stereo pairs * 2) */
static double core_fps, core_aspect;
static int64_t wait_ns;             /* time spent waiting for a free screen buffer (vsync) */
static void *rom_data;
static uint16_t *line;              /* one converted line, for XRGB8888 cores */

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
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        int f = *(const enum retro_pixel_format *)data;
        if (f != RETRO_PIXEL_FORMAT_RGB565 && f != RETRO_PIXEL_FORMAT_XRGB8888) return false;
        pixfmt = f; return true;
    }
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = sys_dir; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
        if (data) *(bool *)data = true;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = false; return true;
    default:
        return false;               /* everything else: core uses its defaults */
    }
}

static inline uint16_t to565(uint32_t p) {
    return (uint16_t)(((p >> 8) & 0xF800) | ((p >> 5) & 0x07E0) | ((p >> 3) & 0x001F));
}

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !window) return;   /* NULL = duplicate frame, keep the last one */
    /* Integer upscale so the TV's scaler keeps pixels sharp: 4x for low-res modes,
     * 2x for hi-res (512 wide) and interlaced (448 tall) modes */
    unsigned sx = w > 400 ? 2 : 4, sy = h > 300 ? 2 : 4;
    if (win_w != (int)(w * sx) || win_h != (int)(h * sy)) {
        win_w = w * sx; win_h = h * sy;
        ANativeWindow_setBuffersGeometry(window, win_w, win_h, WINDOW_FORMAT_RGB_565);
        free(line); line = malloc(w * 2);
    }
    ANativeWindow_Buffer buf;
    int64_t t = now_ns();
    int locked = ANativeWindow_lock(window, &buf, NULL);
    wait_ns += now_ns() - t;
    if (locked != 0) return;
    for (unsigned y = 0; y < h; y++) {
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        const uint16_t *src = (const uint16_t *)row;
        if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            for (unsigned x = 0; x < w; x++) line[x] = to565(((const uint32_t *)row)[x]);
            src = line;
        }
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

static void *sym(const char *name) {
    void *p = dlsym(core.lib, name);
    if (!p) LOG("missing %s", name);
    return p;
}

static bool write_file(const char *path, const void *data, size_t size) {
    FILE *f = fopen(path, "wb");
    if (!f) return false;
    bool ok = fwrite(data, 1, size, f) == size;
    return fclose(f) == 0 && ok;
}

/* Reads a whole file; returns NULL if missing. */
static void *read_file(const char *path, long *size) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END); *size = ftell(f); fseek(f, 0, SEEK_SET);
    void *d = malloc(*size > 0 ? *size : 1);
    if (fread(d, 1, *size, f) != (size_t)*size) { free(d); d = NULL; }
    fclose(f);
    return d;
}

static void unload(void) {
    if (!core.lib) return;
    core.unload_game();
    core.deinit();
    dlclose(core.lib);
    memset(&core, 0, sizeof core);
    free(rom_data); rom_data = NULL;
}

/* Loads core library corePath and the game romPath. Returns the audio sample rate, or 0 on failure. */
JNIEXPORT jint JNICALL
JNI(nativeLoad)(JNIEnv *env, jclass c, jstring jcore, jstring jrom, jstring jdir) {
    unload();
    const char *corepath = (*env)->GetStringUTFChars(env, jcore, NULL);
    const char *rom = (*env)->GetStringUTFChars(env, jrom, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    snprintf(sys_dir, sizeof sys_dir, "%s", dir);
    int rate = 0;
    long size = 0;

    core.lib = dlopen(corepath, RTLD_NOW | RTLD_LOCAL);
    if (!core.lib) { LOG("dlopen %s: %s", corepath, dlerror()); goto done; }
    *(void **)&core.set_environment = sym("retro_set_environment");
    *(void **)&core.set_video_refresh = sym("retro_set_video_refresh");
    *(void **)&core.set_audio_sample = sym("retro_set_audio_sample");
    *(void **)&core.set_audio_sample_batch = sym("retro_set_audio_sample_batch");
    *(void **)&core.set_input_poll = sym("retro_set_input_poll");
    *(void **)&core.set_input_state = sym("retro_set_input_state");
    *(void **)&core.init = sym("retro_init");
    *(void **)&core.deinit = sym("retro_deinit");
    *(void **)&core.get_system_info = sym("retro_get_system_info");
    *(void **)&core.get_system_av_info = sym("retro_get_system_av_info");
    *(void **)&core.load_game = sym("retro_load_game");
    *(void **)&core.unload_game = sym("retro_unload_game");
    *(void **)&core.run = sym("retro_run");
    *(void **)&core.reset = sym("retro_reset");
    *(void **)&core.serialize_size = sym("retro_serialize_size");
    *(void **)&core.serialize = sym("retro_serialize");
    *(void **)&core.unserialize = sym("retro_unserialize");
    *(void **)&core.get_memory_data = sym("retro_get_memory_data");
    *(void **)&core.get_memory_size = sym("retro_get_memory_size");

    pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
    core.set_environment(env_cb);
    core.set_video_refresh(video_cb);
    core.set_audio_sample(audio_cb);
    core.set_audio_sample_batch(audio_batch_cb);
    core.set_input_poll(input_poll_cb);
    core.set_input_state(input_state_cb);
    core.init();

    /* Pass the ROM both in memory and by path, so it works whichever way the core loads */
    rom_data = read_file(rom, &size);
    struct retro_game_info info = { rom, rom_data, (size_t)size, NULL };
    if (!rom_data || !core.load_game(&info)) {
        LOG("could not load %s", rom);
        core.deinit(); dlclose(core.lib); memset(&core, 0, sizeof core);
        free(rom_data); rom_data = NULL;
        goto done;
    }
    struct retro_system_av_info av;
    core.get_system_av_info(&av);
    core_fps = av.timing.fps;
    core_aspect = av.geometry.aspect_ratio > 0 ? av.geometry.aspect_ratio
                : (double)av.geometry.base_width / av.geometry.base_height;
    rate = (int)av.timing.sample_rate;
    win_w = win_h = 0;
    LOG("loaded %s: %ux%u %.3f fps, %d Hz", rom, av.geometry.base_width, av.geometry.base_height, core_fps, rate);
done:
    (*env)->ReleaseStringUTFChars(env, jcore, corepath);
    (*env)->ReleaseStringUTFChars(env, jrom, rom);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    return rate;
}

JNIEXPORT jdouble JNICALL
JNI(nativeFps)(JNIEnv *env, jclass c) { return core_fps; }

/* Width / height of the picture as the core wants it shown */
JNIEXPORT jdouble JNICALL
JNI(nativeAspect)(JNIEnv *env, jclass c) { return core_aspect; }

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
    core.run();
    jsize cap = (*env)->GetArrayLength(env, out);
    int n = audio_len < cap ? audio_len : cap;
    (*env)->SetShortArrayRegion(env, out, 0, n, audio);
    return n;
}

JNIEXPORT jboolean JNICALL
JNI(nativeSaveState)(JNIEnv *env, jclass c, jstring jpath) {
    size_t size = core.serialize_size();
    void *d = size ? malloc(size) : NULL;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    bool ok = d && core.serialize(d, size) && write_file(path, d, size);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    free(d);
    return ok;
}

JNIEXPORT jboolean JNICALL
JNI(nativeLoadState)(JNIEnv *env, jclass c, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    long size = 0;
    void *d = read_file(path, &size);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    bool ok = d && core.unserialize(d, size);
    free(d);
    return ok;
}

/* Battery-backed save RAM (in-game saves): copy from/to a file */
JNIEXPORT void JNICALL
JNI(nativeSram)(JNIEnv *env, jclass c, jstring jpath, jboolean save) {
    void *ram = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!ram || !size) return;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (save) write_file(path, ram, size);
    else {
        long n = 0;
        void *d = read_file(path, &n);
        if (d) { memcpy(ram, d, (size_t)n < size ? (size_t)n : size); free(d); }
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
}

JNIEXPORT void JNICALL
JNI(nativeReset)(JNIEnv *env, jclass c) { core.reset(); }

JNIEXPORT void JNICALL
JNI(nativeUnload)(JNIEnv *env, jclass c) { unload(); }
