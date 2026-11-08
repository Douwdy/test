#define _USE_MATH_DEFINES
// LightTune objective tests: accuracy, transparency, formants, CPU.
// Synthesises a vowel-like voice (band-limited glottal pulse train through
// formant resonators), detunes it, runs the engine and measures the result
// with an independent, brute-force full-rate YIN pitch estimator.
#include "lighttune/Engine.h"
#include "../cli/wav.h"

#include <chrono>
#include <cstdio>
#include <functional>
#include <random>
#include <vector>

using namespace lighttune;

static const double FS = 44100.0;
static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++failures; std::printf("  FAIL: "); std::printf(__VA_ARGS__); std::printf("\n"); } else { std::printf("  ok:   "); std::printf(__VA_ARGS__); std::printf("\n"); } } while (0)

// --- synthetic voice ---------------------------------------------------------
struct Reson { // 2-pole resonator
    double a1, a2, g, y1 = 0, y2 = 0;
    Reson(double f, double bw) {
        const double r = std::exp(-M_PI * bw / FS);
        a1 = -2 * r * std::cos(2 * M_PI * f / FS); a2 = r * r; g = 1 - r;
    }
    double run(double x) { double y = g * x - a1 * y1 - a2 * y2; y2 = y1; y1 = y; return y; }
};

static std::vector<float> voice(size_t n, const std::function<double(double)>& f0, double formantScale = 1.0) {
    std::vector<float> out(n);
    std::vector<Reson> F = {Reson(730 * formantScale, 90), Reson(1090 * formantScale, 110), Reson(2440 * formantScale, 160)};
    double ph = 0;
    for (size_t i = 0; i < n; ++i) {
        const double t = i / FS, f = f0(t);
        ph += f / FS;
        // band-limited sawtooth (glottal-ish source)
        double s = 0;
        const int H = int(std::min(40.0, 5000.0 / f));
        for (int h = 1; h <= H; ++h) s += std::sin(2 * M_PI * h * ph) / h;
        double y = 0;
        for (auto& r : F) y += r.run(s);
        out[i] = float(y);
    }
    float mx = 0;
    for (float v : out) mx = std::max(mx, std::fabs(v));
    for (float& v : out) v *= 0.5f / mx;
    return out;
}

// --- independent reference pitch measurement (brute-force YIN at full rate) ---
static double refPitch(const std::vector<float>& x, size_t center, double lo = 70, double hi = 1000) {
    const int W = 1200, minL = int(FS / hi), maxL = int(FS / lo);
    if (center < size_t(W + maxL) || center + W >= x.size()) return 0;
    std::vector<double> d(size_t(maxL) + 2, 0);
    const size_t s0 = center - W / 2;
    for (int L = 1; L <= maxL + 1; ++L) {
        double s = 0;
        for (int j = 0; j < W; ++j) { double a = x[s0 + j] - x[s0 + j - L]; s += a * a; }
        d[size_t(L)] = s;
    }
    double run = 0; std::vector<double> c(d.size(), 1);
    for (size_t L = 1; L < d.size(); ++L) { run += d[L]; c[L] = d[L] * L / std::max(run, 1e-20); }
    int best = -1;
    for (int L = minL; L < maxL; ++L) if (c[size_t(L)] < 0.12) { while (c[size_t(L + 1)] < c[size_t(L)]) ++L; best = L; break; }
    if (best < 0) { double mv = 9; for (int L = minL; L < maxL; ++L) if (c[size_t(L)] < mv) { mv = c[size_t(L)]; best = L; } }
    double a = c[size_t(best - 1)], b = c[size_t(best)], cc = c[size_t(best + 1)];
    double lag = best + 0.5 * (a - cc) / std::max(a - 2 * b + cc, 1e-12);
    return FS / lag;
}

static double midiOf(double hz) { return 69 + 12 * std::log2(hz / 440.0); }

static std::vector<float> run(const std::vector<float>& x, const Params& p, double* cpuPct = nullptr, int* latOut = nullptr) {
    Engine e;
    e.prepare(FS, 1, p);
    const int lat = e.latencySamples();
    std::vector<float> in(x), out(x.size() + size_t(lat), 0.f);
    in.resize(out.size(), 0.f);
    const auto t0 = std::chrono::steady_clock::now();
    for (size_t pos = 0; pos < in.size(); pos += 128) {
        const int n = int(std::min<size_t>(128, in.size() - pos));
        const float* ip[1] = {in.data() + pos};
        float* op[1] = {out.data() + pos};
        e.process(ip, op, n);
    }
    const double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    if (cpuPct) *cpuPct = 100.0 * sec / (in.size() / FS);
    if (latOut) *latOut = lat;
    out.erase(out.begin(), out.begin() + lat);
    out.resize(x.size());
    return out;
}

static double rms(const std::vector<float>& x, size_t a, size_t b) {
    double s = 0; for (size_t i = a; i < b; ++i) s += double(x[i]) * x[i]; return std::sqrt(s / double(b - a));
}

// spectral-envelope proxy: centroid of the magnitude spectrum 300..4000 Hz (DFT on a window)
static double centroid(const std::vector<float>& x, size_t center) {
    const int N = 2048; double num = 0, den = 0;
    for (int k = int(300.0 * N / FS); k < int(4000.0 * N / FS); ++k) {
        double re = 0, im = 0;
        for (int j = 0; j < N; ++j) {
            double w = 0.5 - 0.5 * std::cos(2 * M_PI * j / (N - 1));
            double v = x[center - N / 2 + j] * w;
            re += v * std::cos(2 * M_PI * k * j / N); im -= v * std::sin(2 * M_PI * k * j / N);
        }
        double m = std::sqrt(re * re + im * im);
        num += m * k * FS / N; den += m;
    }
    return num / den;
}

int main(int argc, char** argv) {
    const bool dump = argc > 1 && std::string(argv[1]) == "--dump";
    const size_t N = size_t(FS * 3.0);

    // 1. Hard tune: a voice 40 cents sharp of A3 with vibrato must land on A3
    {
        std::printf("[1] hard tune (retune 0, chromatic), input A3 +40 cents with 30-cent vibrato\n");
        auto x = voice(N, [](double t) { return 220.0 * std::pow(2.0, (40 + 30 * std::sin(2 * M_PI * 5.5 * t)) / 1200.0); });
        Params p; p.retuneSpeedMs = 0;
        double cpu; int lat;
        auto y = run(x, p, &cpu, &lat);
        double worst = 0, sum = 0; int n = 0;
        for (size_t c = size_t(FS * 0.5); c < N - size_t(FS * 0.2); c += 441) {
            double cents = 100 * (midiOf(refPitch(y, c)) - 57.0);
            worst = std::max(worst, std::fabs(cents)); sum += std::fabs(cents); ++n;
        }
        CHECK(sum / n < 3.0, "mean |error| = %.2f cents (in: 40 +/- 30 cents)", sum / n);
        CHECK(worst < 12.0, "worst |error| = %.2f cents", worst);
        CHECK(cpu < 2.0, "CPU = %.3f%% of one core (mono, 44.1 kHz), latency %d samples = %.1f ms", cpu, lat, 1000.0 * lat / FS);
        if (dump) { wav::Audio a; a.sampleRate = 44100; a.data = {x}; wav::write("t1_in.wav", a); a.data = {y}; wav::write("t1_out.wav", a); }
    }

    // 2. Scale snapping: glide C4->E4 in C major must output only scale notes when hard-tuned
    {
        std::printf("[2] C major scale snapping on a slow glide C4 -> E4\n");
        auto x = voice(N, [](double t) { return 261.63 * std::pow(2.0, (std::min(t, 2.5) / 2.5) * 4 / 12.0); });
        Params p; p.retuneSpeedMs = 0; p.key = 0; p.scale = Scale::Major; p.applyKeyScale();
        auto y = run(x, p);
        int off = 0, n = 0;
        for (size_t c = size_t(FS * 0.3); c < N - size_t(FS * 0.2); c += 441) {
            double m = midiOf(refPitch(y, c));
            double near = std::round(m);
            int pc = (int(near) % 12 + 12) % 12;
            bool inScale = pc == 0 || pc == 2 || pc == 4;
            if (!inScale || std::fabs(m - near) > 0.15) ++off;
            ++n;
        }
        CHECK(off <= n / 20, "%d/%d frames off-scale (transitions allowed)", off, n);
    }

    // 3. Retune speed: slow retune keeps the vibrato, fast removes it
    {
        std::printf("[3] retune speed vs vibrato depth\n");
        auto x = voice(N, [](double t) { return 220.0 * std::pow(2.0, (50 * std::sin(2 * M_PI * 5.0 * t)) / 1200.0); });
        auto depth = [&](const std::vector<float>& y) {
            double mn = 1e9, mx = -1e9;
            for (size_t c = size_t(FS); c < N - size_t(FS * 0.3); c += 220) { double m = midiOf(refPitch(y, c)); mn = std::min(mn, m); mx = std::max(mx, m); }
            return 100 * (mx - mn) / 2;
        };
        Params fast; fast.retuneSpeedMs = 0;
        Params slow; slow.retuneSpeedMs = 300;
        double df = depth(run(x, fast)), ds = depth(run(x, slow));
        CHECK(df < 10, "retune 0 ms: residual vibrato %.1f cents (input 50)", df);
        CHECK(ds > 35, "retune 300 ms: vibrato kept %.1f cents (input 50)", ds);
    }

    // 4. Transparency: in-tune input + unvoiced noise must come out ~unchanged
    {
        std::printf("[4] transparency on unvoiced noise\n");
        std::mt19937 rng(1); std::normal_distribution<float> g(0, 0.1f);
        std::vector<float> x(N); for (auto& v : x) v = g(rng);
        Params p;
        auto y = run(x, p);
        std::vector<float> e(N); for (size_t i = 0; i < N; ++i) e[i] = y[i] - x[i];
        double snr = 20 * std::log10(rms(x, 4410, N - 4410) / std::max(rms(e, 4410, N - 4410), 1e-12));
        CHECK(snr > 60, "noise passthrough SNR = %.1f dB", snr);
    }

    // 5. Transpose with formant correction keeps the spectral envelope; without, it moves
    {
        std::printf("[5] formant preservation on +5 semitone transpose\n");
        auto x = voice(N, [](double) { return 196.0; });
        Params keep; keep.retuneSpeedMs = 0; keep.transpose = 5;
        Params move = keep; move.formantCorrection = false;
        auto yk = run(x, keep), ym = run(x, move);
        const size_t c = N / 2;
        double c0 = centroid(x, c), ck = centroid(yk, c), cm = centroid(ym, c);
        double pk = refPitch(yk, c);
        CHECK(std::fabs(100 * (midiOf(pk) - midiOf(196.0 * std::pow(2, 5 / 12.0)))) < 5, "output pitch %.2f Hz (expected %.2f)", pk, 196.0 * std::pow(2, 5 / 12.0));
        CHECK(std::fabs(ck / c0 - 1) < 0.06, "formant ON : centroid %.0f Hz vs input %.0f Hz (%.1f%%)", ck, c0, 100 * (ck / c0 - 1));
        CHECK(cm / c0 > 1.15, "formant OFF: centroid %.0f Hz moves up (%.1f%%)", cm, 100 * (cm / c0 - 1));
        Params thr = keep; thr.throatLength = 140;
        double ct = centroid(run(x, thr), c);
        CHECK(ct / c0 < 0.93, "throat 140%%: centroid %.0f Hz moves down (%.1f%%)", ct, 100 * (ct / c0 - 1));
    }

    // 6. Low latency mode still corrects
    {
        std::printf("[6] low latency mode\n");
        auto x = voice(N, [](double) { return 220.0 * std::pow(2.0, 35 / 1200.0); });
        Params p; p.retuneSpeedMs = 0; p.lowLatency = true;
        int lat; auto y = run(x, p, nullptr, &lat);
        double cents = 100 * (midiOf(refPitch(y, N / 2)) - 57.0);
        CHECK(std::fabs(cents) < 5, "error %.2f cents, latency %.1f ms", cents, 1000.0 * lat / FS);
    }

    // 7. Pitch range per input type: low male at 70 Hz, soprano at 880 Hz
    {
        std::printf("[7] extreme ranges\n");
        auto lo = voice(N, [](double) { return 73.42 * std::pow(2.0, -30 / 1200.0); }); // D2 -30c
        Params pl; pl.inputType = InputType::LowMale; pl.retuneSpeedMs = 0;
        double el = 100 * (midiOf(refPitch(run(lo, pl), N / 2, 50, 400)) - 38.0);
        CHECK(std::fabs(el) < 5, "low male D2-30c -> error %.2f cents", el);
        auto hi = voice(N, [](double) { return 880.0 * std::pow(2.0, 30 / 1200.0); });  // A5 +30c
        Params ph; ph.inputType = InputType::Soprano; ph.retuneSpeedMs = 0;
        double eh = 100 * (midiOf(refPitch(run(hi, ph), N / 2, 200, 1300)) - 81.0);
        CHECK(std::fabs(eh) < 5, "soprano A5+30c -> error %.2f cents", eh);
    }

    // 8. Stereo CPU benchmark at 48 kHz with everything enabled
    {
        std::printf("[8] stereo 48 kHz, all features enabled\n");
        const double fs = 48000; const size_t n = size_t(fs * 10);
        auto x = voice(n, [](double t) { return 200.0 + 30 * std::sin(t); });
        Params p; p.humanize = 50; p.flexTune = 30; p.naturalVibratoDb = 3; p.vibPitchCents = 40; p.vibAmplitudePct = 20; p.vibFormantPct = 20; p.throatLength = 120;
        Engine e; e.prepare(fs, 2, p);
        std::vector<float> L(x), R(x), oL(n), oR(n);
        const auto t0 = std::chrono::steady_clock::now();
        for (size_t pos = 0; pos + 256 <= n; pos += 256) {
            const float* ip[2] = {L.data() + pos, R.data() + pos}; float* op[2] = {oL.data() + pos, oR.data() + pos};
            e.process(ip, op, 256);
        }
        const double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        CHECK(100 * sec / 10 < 3.0, "CPU = %.3f%% of one core", 100 * sec / 10);
    }

    // 9. Robustness: random parameter automation, silence, impulses, DC, clipping, odd sample rates
    {
        std::printf("[9] robustness (random automation, odd signals, 22.05 .. 192 kHz)\n");
        std::mt19937 rng(7);
        std::uniform_real_distribution<float> U(0.f, 1.f);
        bool finite = true; float peak = 0;
        for (double fs : {22050.0, 44100.0, 96000.0, 192000.0}) {
            Params p; Engine e; e.prepare(fs, 2, p);
            std::vector<float> L(512), R(512);
            for (int b = 0; b < int(fs * 4 / 512); ++b) {
                const int kind = (b / 20) % 5;
                for (int i = 0; i < 512; ++i) {
                    const double t = (b * 512 + i) / fs;
                    float v = 0;
                    if (kind == 1) v = float(0.9 * std::sin(2 * M_PI * (150 + 400 * U(rng) * 0.01) * t));
                    if (kind == 2) v = (i == 0) ? 1.f : 0.f;
                    if (kind == 3) v = 0.5f;
                    if (kind == 4) v = std::sin(2 * M_PI * 300 * t) > 0 ? 1.f : -1.f;
                    L[size_t(i)] = v; R[size_t(i)] = -v;
                }
                if (b % 7 == 0) {
                    p.retuneSpeedMs = 400 * U(rng); p.humanize = 100 * U(rng); p.flexTune = 100 * U(rng);
                    p.naturalVibratoDb = 24 * U(rng) - 12; p.transpose = std::round(48 * U(rng) - 24);
                    p.throatLength = 50 + 130 * U(rng); p.formantCorrection = U(rng) > 0.3f; p.classicMode = U(rng) > 0.8f;
                    p.vibPitchCents = 200 * U(rng); p.vibAmplitudePct = 100 * U(rng); p.vibFormantPct = 100 * U(rng);
                    p.vibVariation = 100 * U(rng); p.scale = Scale(int(U(rng) * 15.99f)); p.key = int(U(rng) * 11.99f);
                    p.applyKeyScale(); p.mix = 100 * U(rng); p.tracking = 100 * U(rng);
                    e.setParams(p);
                }
                const float* ip[2] = {L.data(), R.data()}; float* op[2] = {L.data(), R.data()};
                e.process(ip, op, 512);
                for (int i = 0; i < 512; ++i) { finite = finite && std::isfinite(L[size_t(i)]) && std::isfinite(R[size_t(i)]); peak = std::max(peak, std::fabs(L[size_t(i)])); }
            }
        }
        CHECK(finite, "no NaN / Inf");
        CHECK(peak < 4.f, "output bounded (peak %.2f for input peak 1.0)", peak);
    }

    std::printf(failures ? "\n%d FAILURE(S)\n" : "\nALL TESTS PASSED\n", failures);
    return failures ? 1 : 0;
}
