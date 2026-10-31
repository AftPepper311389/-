// rlin client —— 游戏内渲染钩子
//
// 做法：用 ShadowHook 钩住 libEGL.so 的 eglSwapBuffers。每一帧游戏画完、上屏之前：
//   1. 把需要模糊的那块屏幕区域拷贝到一张纹理（glCopyTexSubImage2D，只拷并集区域）
//   2. 双重 Kawase 模糊（降采样 N 次 → 升采样回来）
//   3. 对每个矩形：用模糊结果 + 暗色着色 + 圆角遮罩画回屏幕
// 绘制前后完整保存 / 恢复 GL 状态，尽量不影响游戏自己的渲染。
//
// 要求 OpenGL ES 3.0+、默认帧缓冲不是多重采样。Vulkan 后端这个库没用（钩不到）。

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <algorithm>
#include <mutex>
#include <vector>

#include "shadowhook.h"

#define TAG "rlin"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ------------------------------------------------------------------ 配置（Java 层可改）
struct Rect { float x, y, w, h, r, a; };         // 屏幕像素，原点左上角；a = 着色强度 0..1

std::mutex g_cfgMutex;
std::vector<Rect> g_rects;                       // 要做模糊的矩形
std::atomic<bool> g_demo{false};
std::atomic<bool> g_light{false};                 // 浅色主题：模糊着色换成暖白
std::atomic<int> g_levels{3};                    // 降采样次数 1..4
std::atomic<float> g_offset{2.0f};               // 采样偏移 1..4
std::atomic<int> g_fps{0};

// 诊断计数（心跳线程每隔几秒打印一次，用来判断游戏到底走的是哪条渲染路径）
std::atomic<int> g_cSwap{0}, g_cSwapDamage{0}, g_cMakeCurrent{0}, g_cVkPresent{0}, g_cCreateCtx{0};
std::atomic<int> g_cEarly{0};

// ------------------------------------------------------------------ 上下文登记
// 进程里可能有好几个 EGL 上下文（游戏主画面、第三方面板、弹窗…）。只在“游戏自己的”主上下文里画，
// 其它的一律不碰（连 GL 调用都不做）。判断依据：eglCreateContext 的调用者在 libminecraftpe.so 里。
struct CtxInfo { EGLContext ctx; bool fromGame; int swaps; char lib[96]; };
constexpr int kMaxCtx = 16;
CtxInfo g_ctxs[kMaxCtx];
int g_nCtx = 0;
bool g_anyFromGame = false;
std::mutex g_ctxMutex;
std::atomic<int> g_totalSwaps{0};

CtxInfo* findCtx(EGLContext c) {            // 调用时持有 g_ctxMutex
    for (int i = 0; i < g_nCtx; i++) if (g_ctxs[i].ctx == c) return &g_ctxs[i];
    return nullptr;
}

// 这个上下文能不能画：来自游戏的主上下文；如果一直没发现来自游戏的上下文，300 帧后退而求其次选交换次数最多的
bool ctxAllowed(EGLContext c) {
    std::lock_guard<std::mutex> lk(g_ctxMutex);
    CtxInfo* me = findCtx(c);
    if (!me) {                              // 钩子装上之前就创建好的上下文：登记为未知
        if (g_nCtx < kMaxCtx) { g_ctxs[g_nCtx] = {c, false, 0, "?"}; me = &g_ctxs[g_nCtx++]; }
        else return false;
    }
    me->swaps++;
    if (me->fromGame) return true;
    if (g_anyFromGame || g_totalSwaps.load() < 300) return false;
    for (int i = 0; i < g_nCtx; i++) if (g_ctxs[i].swaps > me->swaps) return false;
    return true;
}

// ------------------------------------------------------------------ 着色器
const char* kVert = R"(#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
})";

const char* kDown = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uHalf;
in vec2 vUv;
out vec4 o;
void main() {
    vec4 s = texture(uTex, vUv) * 4.0;
    s += texture(uTex, vUv - uHalf);
    s += texture(uTex, vUv + uHalf);
    s += texture(uTex, vUv + vec2(uHalf.x, -uHalf.y));
    s += texture(uTex, vUv - vec2(uHalf.x, -uHalf.y));
    o = s / 8.0;
})";

const char* kUp = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uHalf;
in vec2 vUv;
out vec4 o;
void main() {
    vec4 s = texture(uTex, vUv + vec2(-uHalf.x * 2.0, 0.0));
    s += texture(uTex, vUv + vec2(-uHalf.x, uHalf.y)) * 2.0;
    s += texture(uTex, vUv + vec2(0.0, uHalf.y * 2.0));
    s += texture(uTex, vUv + vec2(uHalf.x, uHalf.y)) * 2.0;
    s += texture(uTex, vUv + vec2(uHalf.x * 2.0, 0.0));
    s += texture(uTex, vUv + vec2(uHalf.x, -uHalf.y)) * 2.0;
    s += texture(uTex, vUv + vec2(0.0, -uHalf.y * 2.0));
    s += texture(uTex, vUv + vec2(-uHalf.x, -uHalf.y)) * 2.0;
    o = s / 12.0;
})";

// 合成：gl_FragCoord 是屏幕像素（原点左下）。用圆角矩形 SDF 做遮罩，边缘 1px 抗锯齿
const char* kComp = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec4 uRegion;   // 拷贝区域 x, y, w, h（GL 坐标）
uniform vec4 uRect;     // 矩形 x, y, w, h（GL 坐标）
uniform float uRadius;
uniform vec4 uTint;     // rgb = 着色, a = 着色强度
out vec4 o;
void main() {
    vec2 uv = (gl_FragCoord.xy - uRegion.xy) / uRegion.zw;
    vec3 blur = texture(uTex, uv).rgb;
    vec2 half_ = uRect.zw * 0.5;
    vec2 p = gl_FragCoord.xy - (uRect.xy + half_);
    float r = min(uRadius, min(half_.x, half_.y));
    vec2 q = abs(p) - (half_ - vec2(r));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float mask = clamp(0.5 - d, 0.0, 1.0);
    // 通透感：模糊结果略提饱和度和亮度（像光穿过毛玻璃），顶部微微更亮，边缘一圈细亮边
    float lum = dot(blur, vec3(0.299, 0.587, 0.114));
    blur = clamp(mix(vec3(lum), blur, 1.22) + 0.018, 0.0, 1.0);
    vec3 rgb = mix(blur, uTint.rgb, uTint.a);
    rgb += 0.032 * clamp(p.y / max(half_.y, 1.0), -1.0, 1.0);
    rgb += clamp(1.0 + d / 1.6, 0.0, 1.0) * 0.10;
    o = vec4(rgb, mask);
})";

// ------------------------------------------------------------------ GL 状态保存 / 恢复
struct GlState {
    GLint program, arrayBuf, vao, activeTex, tex0, sampler0, tex1, sampler1, fbDraw, fbRead;
    GLint viewport[4], scissor[4];
    GLint blendSrcRgb, blendDstRgb, blendSrcA, blendDstA, blendEqRgb, blendEqA;
    GLboolean colorMask[4];
    GLboolean blend, depth, scissorTest, stencil, cull, dither, alphaToCov, discard;

    void save() {
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuf);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTex);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex0);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler0);
        glActiveTexture(GL_TEXTURE1);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex1);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler1);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &fbDraw);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &fbRead);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_SCISSOR_BOX, scissor);
        glGetIntegerv(GL_BLEND_SRC_RGB, &blendSrcRgb);
        glGetIntegerv(GL_BLEND_DST_RGB, &blendDstRgb);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &blendSrcA);
        glGetIntegerv(GL_BLEND_DST_ALPHA, &blendDstA);
        glGetIntegerv(GL_BLEND_EQUATION_RGB, &blendEqRgb);
        glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &blendEqA);
        glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
        blend = glIsEnabled(GL_BLEND);
        depth = glIsEnabled(GL_DEPTH_TEST);
        scissorTest = glIsEnabled(GL_SCISSOR_TEST);
        stencil = glIsEnabled(GL_STENCIL_TEST);
        cull = glIsEnabled(GL_CULL_FACE);
        dither = glIsEnabled(GL_DITHER);
        alphaToCov = glIsEnabled(GL_SAMPLE_ALPHA_TO_COVERAGE);
        discard = glIsEnabled(GL_RASTERIZER_DISCARD);
    }

    static void en(GLenum cap, GLboolean on) { if (on) glEnable(cap); else glDisable(cap); }

    void restore() {
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuf);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex0);
        glBindSampler(0, sampler0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, tex1);
        glBindSampler(1, sampler1);
        glActiveTexture(GL_TEXTURE0);
        glActiveTexture(activeTex);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbDraw);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbRead);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcA, blendDstA);
        glBlendEquationSeparate(blendEqRgb, blendEqA);
        glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        en(GL_BLEND, blend); en(GL_DEPTH_TEST, depth); en(GL_SCISSOR_TEST, scissorTest);
        en(GL_STENCIL_TEST, stencil); en(GL_CULL_FACE, cull); en(GL_DITHER, dither);
        en(GL_SAMPLE_ALPHA_TO_COVERAGE, alphaToCov); en(GL_RASTERIZER_DISCARD, discard);
    }
};

// ------------------------------------------------------------------ 渲染器
constexpr int kMaxLevels = 4;

struct Renderer {
    bool ready = false, failed = false;
    GLuint progDown = 0, progUp = 0, progComp = 0, vbo = 0, vao = 0, fbo = 0;
    GLuint tex[kMaxLevels + 1] = {0};
    int texW[kMaxLevels + 1] = {0}, texH[kMaxLevels + 1] = {0};
    GLint uDownTex, uDownHalf, uUpTex, uUpHalf, uCTex, uCRegion, uCRect, uCRadius, uCTint;

    static GLuint compile(GLenum type, const char* src) {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[512]; glGetShaderInfoLog(s, sizeof(log), nullptr, log);
            LOGE("shader compile failed: %s", log);
            glDeleteShader(s);
            return 0;
        }
        return s;
    }

    static GLuint link(const char* fs) {
        GLuint v = compile(GL_VERTEX_SHADER, kVert);
        GLuint f = compile(GL_FRAGMENT_SHADER, fs);
        if (!v || !f) return 0;
        GLuint p = glCreateProgram();
        glAttachShader(p, v); glAttachShader(p, f);
        glLinkProgram(p);
        glDeleteShader(v); glDeleteShader(f);
        GLint ok = 0;
        glGetProgramiv(p, GL_LINK_STATUS, &ok);
        if (!ok) {
            char log[512]; glGetProgramInfoLog(p, sizeof(log), nullptr, log);
            LOGE("program link failed: %s", log);
            glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    bool init() {
        progDown = link(kDown); progUp = link(kUp); progComp = link(kComp);
        if (!progDown || !progUp || !progComp) return false;
        uDownTex = glGetUniformLocation(progDown, "uTex");
        uDownHalf = glGetUniformLocation(progDown, "uHalf");
        uUpTex = glGetUniformLocation(progUp, "uTex");
        uUpHalf = glGetUniformLocation(progUp, "uHalf");
        uCTex = glGetUniformLocation(progComp, "uTex");
        uCRegion = glGetUniformLocation(progComp, "uRegion");
        uCRect = glGetUniformLocation(progComp, "uRect");
        uCRadius = glGetUniformLocation(progComp, "uRadius");
        uCTint = glGetUniformLocation(progComp, "uTint");
        static const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
        glGenVertexArrays(1, &vao);
        glBindVertexArray(vao);
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glGenFramebuffers(1, &fbo);
        glGenTextures(kMaxLevels + 1, tex);
        return true;
    }

    // 保证第 i 级纹理是 w x h；只在尺寸变化时重新分配
    void ensureTex(int i, int w, int h) {
        if (texW[i] == w && texH[i] == h) return;
        glBindTexture(GL_TEXTURE_2D, tex[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        texW[i] = w; texH[i] = h;
    }

    // 把 src 级的纹理画到 dst 级（dst 的 FBO 附件 = tex[dst]）
    void pass(GLuint prog, GLint uTex, GLint uHalf, int src, int dst, float offset) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[dst], 0);
        glViewport(0, 0, texW[dst], texH[dst]);
        glUseProgram(prog);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex[src]);
        glUniform1i(uTex, 0);
        glUniform2f(uHalf, 0.5f / texW[src] * offset, 0.5f / texH[src] * offset);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    void draw(int sw, int sh, const std::vector<Rect>& rects) {
        if (rects.empty()) return;
        int levels = std::clamp(g_levels.load(), 1, kMaxLevels);
        float offset = std::clamp(g_offset.load(), 1.0f, 4.0f);

        // 并集区域（GL 坐标：原点左下），外扩一圈避免边缘取样不足
        float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
        for (const Rect& r : rects) {
            minX = std::min(minX, r.x); maxX = std::max(maxX, r.x + r.w);
            float gy = sh - (r.y + r.h);
            minY = std::min(minY, gy); maxY = std::max(maxY, gy + r.h);
        }
        int pad = 6 * (1 << levels);
        int rx = std::max(0, (int)minX - pad), ry = std::max(0, (int)minY - pad);
        int rw = std::min(sw, (int)maxX + pad) - rx, rh = std::min(sh, (int)maxY + pad) - ry;
        if (rw < 8 || rh < 8) return;

        ensureTex(0, rw, rh);
        for (int i = 1; i <= levels; i++) ensureTex(i, std::max(1, rw >> i), std::max(1, rh >> i));

        glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST); glDisable(GL_CULL_FACE); glDisable(GL_DITHER);
        glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE); glDisable(GL_RASTERIZER_DISCARD);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glActiveTexture(GL_TEXTURE0);
        glBindSampler(0, 0);

        // 1) 拷贝屏幕区域
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, tex[0]);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, rx, ry, rw, rh);

        // 2) 降采样 → 升采样
        for (int i = 1; i <= levels; i++) pass(progDown, uDownTex, uDownHalf, i - 1, i, offset);
        for (int i = levels; i >= 2; i--) pass(progUp, uUpTex, uUpHalf, i, i - 1, offset);

        // 3) 合成回屏幕（模糊结果在 tex[1]，半分辨率，双线性放大）
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glViewport(0, 0, sw, sh);
        const bool light = g_light.load();
        glUseProgram(progComp);
        glBindTexture(GL_TEXTURE_2D, tex[1]);
        glUniform1i(uCTex, 0);
        glUniform4f(uCRegion, (float)rx, (float)ry, (float)rw, (float)rh);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_SCISSOR_TEST);
        for (const Rect& r : rects) {
            float gy = sh - (r.y + r.h);
            glScissor((GLint)r.x - 1, (GLint)gy - 1, (GLsizei)r.w + 2, (GLsizei)r.h + 2);
            GLint uR = uCRect, uRad = uCRadius, uT = uCTint;
            glUniform4f(uR, r.x, gy, r.w, r.h);
            glUniform1f(uRad, r.r);
            if (light) glUniform4f(uT, 0.96f, 0.94f, 0.90f, r.a * 0.9f);   // 浅色：暖白
            else glUniform4f(uT, 0.13f, 0.11f, 0.08f, r.a);   // 暖色墨底，和界面配色一致
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
    }
};

Renderer g_r;
EGLContext g_ctx = EGL_NO_CONTEXT;

// ------------------------------------------------------------------ 每帧回调
void countFps() {
    static int frames = 0;
    static long long last = 0;
    timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    long long now = (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    if (last == 0) last = now;
    frames++;
    if (now - last >= 1000) {
        g_fps.store((int)(frames * 1000LL / (now - last)));
        frames = 0; last = now;
    }
}

void onFrame(EGLDisplay dpy, EGLSurface surf) {
    EGLContext ctx = eglGetCurrentContext();
    EGLint w = 0, h = 0;
    eglQuerySurface(dpy, surf, EGL_WIDTH, &w);
    eglQuerySurface(dpy, surf, EGL_HEIGHT, &h);
    if (ctx == EGL_NO_CONTEXT || w < 640 || h < 360) {   // 没有当前上下文 / 不是主画面的 surface
        if (g_cEarly.fetch_add(1) < 8)
            LOGI("swap skipped: ctx=%p surf=%p size=%dx%d tid=%d", ctx, surf, w, h, (int)gettid());
        return;
    }
    g_totalSwaps.fetch_add(1);
    if (!ctxAllowed(ctx)) {
        if (g_cEarly.fetch_add(1) < 8) LOGI("swap ignored (not the game's main context): ctx=%p %dx%d", ctx, w, h);
        return;
    }
    countFps();

    if (ctx != g_ctx) {                          // 上下文变了（首次 / 游戏重建）：资源作废
        g_ctx = ctx;
        g_r = Renderer();
        const char* ver = (const char*)glGetString(GL_VERSION);
        LOGI("new context %p, %dx%d, %s", ctx, w, h, ver ? ver : "?");
        if (!ver || !strstr(ver, "OpenGL ES 3")) { g_r.failed = true; LOGE("need OpenGL ES 3.0+"); }
    }
    if (g_r.failed) return;

    std::vector<Rect> rects;
    {
        std::lock_guard<std::mutex> lk(g_cfgMutex);
        rects = g_rects;
    }
    if (rects.empty() && g_demo.load()) {
        float rw = std::min(520.0f, w * 0.6f);
        rects.push_back({(w - rw) / 2.0f, 60.0f, rw, 140.0f, 28.0f, 0.42f});
    }
    if (rects.empty()) return;

    GlState st;
    st.save();
    if (!g_r.ready) {
        g_r.ready = g_r.init();
        if (!g_r.ready) { g_r.failed = true; LOGE("renderer init failed"); st.restore(); return; }
        LOGI("renderer ready");
    }
    g_r.draw(w, h, rects);
    st.restore();
}

// ------------------------------------------------------------------ 钩子
void* g_stub = nullptr;

EGLBoolean proxy_eglSwapBuffers(EGLDisplay dpy, EGLSurface surf) {
    SHADOWHOOK_STACK_SCOPE();
    g_cSwap.fetch_add(1);
    onFrame(dpy, surf);
    return SHADOWHOOK_CALL_PREV(proxy_eglSwapBuffers, dpy, surf);
}

EGLBoolean proxy_eglSwapBuffersWithDamage(EGLDisplay dpy, EGLSurface surf, EGLint* rects, EGLint n) {
    SHADOWHOOK_STACK_SCOPE();
    g_cSwapDamage.fetch_add(1);
    onFrame(dpy, surf);
    return SHADOWHOOK_CALL_PREV(proxy_eglSwapBuffersWithDamage, dpy, surf, rects, n);
}

EGLBoolean proxy_eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    SHADOWHOOK_STACK_SCOPE();
    int n = g_cMakeCurrent.fetch_add(1);
    if (n < 6) LOGI("eglMakeCurrent ctx=%p draw=%p tid=%d", ctx, draw, (int)gettid());
    return SHADOWHOOK_CALL_PREV(proxy_eglMakeCurrent, dpy, draw, read, ctx);
}

EGLContext proxy_eglCreateContext(EGLDisplay dpy, EGLConfig cfg, EGLContext share, const EGLint* attribs) {
    SHADOWHOOK_STACK_SCOPE();
    int ver = 1;
    for (int i = 0; attribs && attribs[i] != 0x3038 /*EGL_NONE*/; i += 2)
        if (attribs[i] == 0x3098 /*EGL_CONTEXT_CLIENT_VERSION*/) ver = attribs[i + 1];
    void* ret = SHADOWHOOK_RETURN_ADDRESS();
    EGLContext c = SHADOWHOOK_CALL_PREV(proxy_eglCreateContext, dpy, cfg, share, attribs);
    Dl_info di;
    const char* lib = (ret && dladdr(ret, &di) && di.dli_fname) ? di.dli_fname : "?";
    bool fromGame = strstr(lib, "minecraftpe") != nullptr;
    if (c != EGL_NO_CONTEXT) {
        std::lock_guard<std::mutex> lk(g_ctxMutex);
        if (g_nCtx < kMaxCtx && !findCtx(c)) {
            CtxInfo& ci = g_ctxs[g_nCtx++];
            ci.ctx = c; ci.fromGame = fromGame; ci.swaps = 0;
            strncpy(ci.lib, lib, sizeof(ci.lib) - 1); ci.lib[sizeof(ci.lib) - 1] = 0;
            if (fromGame) g_anyFromGame = true;
        }
    }
    LOGI("eglCreateContext client_version=%d -> %p (tid=%d) creator=%s fromGame=%d", ver, c, (int)gettid(), lib, (int)fromGame);
    g_cCreateCtx.fetch_add(1);
    return c;
}

// 只为了探测游戏是不是走 Vulkan：数 vkQueuePresentKHR 的调用次数，不做任何绘制
void* proxy_vkQueuePresentKHR(void* queue, const void* info) {
    SHADOWHOOK_STACK_SCOPE();
    g_cVkPresent.fetch_add(1);
    return SHADOWHOOK_CALL_PREV(proxy_vkQueuePresentKHR, queue, info);
}

void* g_vkStub = nullptr;

// 心跳线程：每 3 秒打一行计数（前 60 秒），之后每 30 秒一行；顺便等 libvulkan 被加载后再装探测钩子
void* heartbeat(void*) {
    int tick = 0;
    for (;;) {
        sleep(3);
        tick++;
        if (!g_vkStub && dlopen("libvulkan.so", RTLD_NOW | RTLD_NOLOAD)) {
            g_vkStub = shadowhook_hook_sym_name("libvulkan.so", "vkQueuePresentKHR",
                                                (void*)proxy_vkQueuePresentKHR, nullptr);
            LOGI("libvulkan is loaded in this process; probe hook %s", g_vkStub ? "installed" : "FAILED");
        }
        if (tick <= 20 || tick % 10 == 0)
            LOGI("hb: swap=%d swapDamage=%d makeCurrent=%d createCtx=%d vkPresent=%d fps=%d",
                 g_cSwap.load(), g_cSwapDamage.load(), g_cMakeCurrent.load(), g_cCreateCtx.load(),
                 g_cVkPresent.load(), g_fps.load());
    }
    return nullptr;
}

}  // namespace

// ------------------------------------------------------------------ JNI
extern "C" {

JNIEXPORT jboolean JNICALL Java_com_rlin_inject_Native_nativeInit(JNIEnv*, jobject) {
    if (g_stub) return JNI_TRUE;
    int rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (rc != 0) { LOGE("shadowhook_init failed: %d", rc); return JNI_FALSE; }
    g_stub = shadowhook_hook_sym_name("libEGL.so", "eglSwapBuffers", (void*)proxy_eglSwapBuffers, nullptr);
    if (!g_stub) {
        int e = shadowhook_get_errno();
        LOGE("hook eglSwapBuffers failed: %d %s", e, shadowhook_to_errmsg(e));
        return JNI_FALSE;
    }
    LOGI("eglSwapBuffers hooked");
    // 诊断用的额外钩子：失败不影响主流程
    shadowhook_hook_sym_name("libEGL.so", "eglSwapBuffersWithDamageKHR", (void*)proxy_eglSwapBuffersWithDamage, nullptr);
    shadowhook_hook_sym_name("libEGL.so", "eglSwapBuffersWithDamageEXT", (void*)proxy_eglSwapBuffersWithDamage, nullptr);
    shadowhook_hook_sym_name("libEGL.so", "eglMakeCurrent", (void*)proxy_eglMakeCurrent, nullptr);
    shadowhook_hook_sym_name("libEGL.so", "eglCreateContext", (void*)proxy_eglCreateContext, nullptr);
    pthread_t th;
    if (pthread_create(&th, nullptr, heartbeat, nullptr) == 0) pthread_detach(th);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_rlin_inject_Native_nativeSetDemo(JNIEnv*, jobject, jboolean on) {
    g_demo.store(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_rlin_inject_Native_nativeSetRects(JNIEnv* env, jobject, jfloatArray arr) {
    std::vector<Rect> v;
    if (arr) {
        jsize n = env->GetArrayLength(arr);
        std::vector<float> f(n);
        env->GetFloatArrayRegion(arr, 0, n, f.data());
        for (jsize i = 0; i + 5 < n; i += 6) v.push_back({f[i], f[i + 1], f[i + 2], f[i + 3], f[i + 4], f[i + 5]});
    }
    std::lock_guard<std::mutex> lk(g_cfgMutex);
    g_rects.swap(v);
}

JNIEXPORT void JNICALL Java_com_rlin_inject_Native_nativeSetBlur(JNIEnv*, jobject, jint levels, jfloat offset) {
    g_levels.store(levels);
    g_offset.store(offset);
}

JNIEXPORT void JNICALL Java_com_rlin_inject_Native_nativeSetLight(JNIEnv*, jobject, jboolean light) {
    g_light.store(light == JNI_TRUE);
}

JNIEXPORT jint JNICALL Java_com_rlin_inject_Native_nativeFps(JNIEnv*, jobject) {
    return g_fps.load();
}

}  // extern "C"
