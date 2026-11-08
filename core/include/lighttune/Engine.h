// LightTune - lightweight real-time pitch correction
// Engine.h : complete real-time processor (detector + corrector + PSOLA shifter)
//
// Pitch shifting uses TD-PSOLA (Time-Domain Pitch-Synchronous Overlap-Add):
//  * analysis marks are placed one period apart on the voiced signal and
//    phase-aligned by a short cross-correlation search;
//  * every synthesis mark takes the closest analysis grain (2 periods,
//    Hann-windowed) and overlap-adds it at the new period spacing.
// Because each grain is one glottal pulse, the spectral envelope (formants)
// is preserved for free: no FFT, no cepstrum, a few multiply-adds per sample.
// Formant shifting / throat modelling is done by resampling each grain
// (reading it slightly faster or slower), which moves the envelope
// independently of the pitch. Unvoiced parts (s, t, breath) are rebuilt with
// a perfect-reconstruction 50% Hann OLA, i.e. passed through untouched.
#pragma once

#include "PitchCorrector.h"
#include "PitchDetector.h"

#include <array>
#include <atomic>
#include <bitset>
#include <cmath>
#include <cstdint>
#include <vector>

namespace lighttune {

class Engine {
public:
    static constexpr int kMaxChannels = 2;

    // Must be called before processing and whenever sampleRate, inputType or
    // lowLatency change (it re-allocates; never call it from the audio thread
    // while processing).
    void prepare(double sampleRate, int numChannels, const Params& p) {
        fs_ = sampleRate;
        numCh_ = std::clamp(numChannels, 1, kMaxChannels);
        params_ = p;
        preparedInput_ = p.inputType;
        preparedLowLatency_ = p.lowLatency;

        double lo, hi;
        inputTypeRange(p.inputType, lo, hi);
        detector_.prepare(fs_, lo, hi);
        detector_.setTracking(p.tracking);
        corrector_.prepare(double(detector_.hopSize()) / fs_);

        pMax_ = fs_ / lo;
        unvHop_ = std::max(32.0, std::round(0.003 * fs_));
        lookahead_ = p.lowLatency ? unvHop_ + 3 : 1.5 * pMax_ + 3;
        latency_ = int(std::ceil(lookahead_ + pMax_ + 8));

        ringSize_ = 1;
        while (ringSize_ < int(latency_ + 4 * pMax_ + 64)) ringSize_ <<= 1;
        for (int c = 0; c < numCh_; ++c) {
            in_[size_t(c)].assign(size_t(ringSize_), 0.f);
            ola_[size_t(c)].assign(size_t(ringSize_), 0.f);
        }
        mono_.assign(size_t(ringSize_), 0.f);

        for (int i = 0; i < kHann; ++i)
            hann_[size_t(i)] = float(0.5 + 0.5 * std::cos(kPi * (2.0 * i / (kHann - 1) - 1.0)));
        hann_[kHann] = 0.f;
        reset();
    }

    void reset() {
        detector_.reset();
        corrector_.reset();
        for (int c = 0; c < numCh_; ++c) {
            std::fill(in_[size_t(c)].begin(), in_[size_t(c)].end(), 0.f);
            std::fill(ola_[size_t(c)].begin(), ola_[size_t(c)].end(), 0.f);
        }
        std::fill(mono_.begin(), mono_.end(), 0.f);
        now_ = 0;
        synth_ = 0;
        marksHead_ = marksCount_ = 0;
        histHead_ = histCount_ = 0;
        voicedNow_ = false;
        curPeriod_ = 0;
        lastMark_ = -1e18;
    }

    // Parameters that do not need re-allocation can be changed at any time.
    // Returns true if prepare() must be called again (input type / latency mode).
    bool setParams(const Params& p) {
        params_ = p;
        detector_.setTracking(p.tracking);
        return p.inputType != preparedInput_ || p.lowLatency != preparedLowLatency_;
    }
    const Params& params() const { return params_; }

    void setMidiNotes(const std::bitset<128>& held) { corrector_.setMidiNotes(held); }

    int latencySamples() const { return latency_; }

    // read-outs for a GUI (thread-safe)
    float inputPitchHz() const { return inHz_.load(std::memory_order_relaxed); }
    float outputPitchHz() const { return outHz_.load(std::memory_order_relaxed); }
    float targetMidi() const { return tgtMidi_.load(std::memory_order_relaxed); }
    float correctionCents() const { return corrCents_.load(std::memory_order_relaxed); }

    // in/out may alias (in-place processing is fine)
    void process(const float* const* in, float* const* out, int numSamples) {
        const int mask = ringSize_ - 1;
        const float wet = std::clamp(params_.mix, 0.f, 100.f) / 100.f;
        const float dry = 1.f - wet;
        for (int i = 0; i < numSamples; ++i) {
            const size_t w = size_t(now_ & mask);
            float m = 0.f;
            for (int c = 0; c < numCh_; ++c) {
                const float v = in[c][i];
                in_[size_t(c)][w] = v;
                m += v;
            }
            m /= float(numCh_);
            mono_[w] = m;
            ++now_; // samples [0, now_) are now available

            if (detector_.push(m)) onEstimate();
            if (voicedNow_) placeMarks();
            while (synth_ <= double(now_) - lookahead_) synthesise();

            const int64_t o = now_ - 1 - latency_;
            const size_t r = size_t(o & mask);
            for (int c = 0; c < numCh_; ++c) {
                const float y = ola_[size_t(c)][r];
                ola_[size_t(c)][r] = 0.f;
                out[c][i] = wet * y + dry * (o >= 0 ? in_[size_t(c)][r] : 0.f);
            }
        }
    }

private:
    struct Mark { double t; float period; };
    struct Hist { int64_t t; bool voiced; float shift; float formant; float gain; };

    void onEstimate() {
        const PitchEstimate& e = detector_.estimate();
        double inMidi = e.voiced ? 69.0 + 12.0 * std::log2(e.frequency / 440.0) : 0.0;
        const Correction c = corrector_.process(e.voiced, inMidi, params_);
        voicedNow_ = e.voiced;
        if (e.voiced) curPeriod_ = e.period;

        Hist h{e.centerTime, e.voiced, c.shiftSemis, c.formantSemis, c.gain};
        histHead_ = (histHead_ + 1) % kHist;
        hist_[size_t(histHead_)] = h;
        histCount_ = std::min(histCount_ + 1, kHist);

        inHz_.store(e.voiced ? float(e.frequency) : 0.f, std::memory_order_relaxed);
        outHz_.store(e.voiced ? float(e.frequency * std::pow(2.0, c.shiftSemis / 12.0)) : 0.f,
                     std::memory_order_relaxed);
        tgtMidi_.store(e.voiced ? c.targetMidi : -1.f, std::memory_order_relaxed);
        corrCents_.store(e.voiced ? c.shiftSemis * 100.f : 0.f, std::memory_order_relaxed);
    }

    // --- analysis marks --------------------------------------------------------
    void placeMarks() {
        const double P = curPeriod_;
        if (P < 8) return;
        const double avail = double(now_);
        if (lastMark_ < avail - 3.0 * P - pMax_) {
            // (re)start: put the first mark on the largest peak of the previous period
            const int64_t a = int64_t(avail - 2.0 * P - 2), b = int64_t(avail - P - 2);
            if (a < 0) return;
            const int mask = ringSize_ - 1;
            int64_t best = a; float bv = -1e30f;
            for (int64_t k = a; k <= b; ++k) {
                const float v = std::fabs(mono_[size_t(k & mask)]);
                if (v > bv) { bv = v; best = k; }
            }
            pushMark(double(best), float(P));
            return;
        }
        const int search = std::max(1, int(P / 8));
        while (lastMark_ + P + P + search + 2 <= avail) {
            double cand = lastMark_ + P;
            cand += refineOffset(lastMark_, cand, P, search);
            pushMark(cand, float(P));
        }
    }

    // pick the offset (in [-search, search]) that best aligns the new grain with the previous one
    double refineOffset(double prev, double cand, double P, int search) const {
        const int mask = ringSize_ - 1;
        const int half = std::max(4, int(P * 0.5));
        const int64_t p0 = int64_t(std::lround(prev));
        const int64_t c0 = int64_t(std::lround(cand));
        double best = -1e30; int bo = 0;
        double sc[512];
        const int n = std::min(2 * search + 1, 512);
        for (int k = 0; k < n; ++k) {
            const int o = k - search;
            double s = 0;
            for (int j = -half; j <= half; j += 2)
                s += double(mono_[size_t((p0 + j) & mask)]) * mono_[size_t((c0 + o + j) & mask)];
            sc[k] = s;
            if (s > best) { best = s; bo = k; }
        }
        double frac = 0;
        if (bo > 0 && bo < n - 1) {
            const double a = sc[bo - 1], b = sc[bo], c = sc[bo + 1];
            const double den = a - 2 * b + c;
            if (den < -1e-20) frac = std::clamp(0.5 * (a - c) / den, -0.5, 0.5);
        }
        // the match is relative to the rounded previous mark: carry its fractional part over
        return double(c0) + double(bo - search) + frac + (prev - double(p0)) - cand;
    }

    void pushMark(double t, float period) {
        marksHead_ = (marksHead_ + 1) % kMarks;
        marks_[size_t(marksHead_)] = Mark{t, period};
        marksCount_ = std::min(marksCount_ + 1, kMarks);
        lastMark_ = t;
    }

    // nearest analysis mark to s whose grain is fully available
    const Mark* nearestMark(double s) const {
        const Mark* best = nullptr;
        double bd = 1e30;
        const double avail = double(now_) - 3.0;
        for (int k = 0; k < marksCount_; ++k) {
            const Mark& m = marks_[size_t((marksHead_ - k + kMarks) % kMarks)];
            if (m.t + m.period > avail) continue;
            const double d = std::fabs(m.t - s);
            if (d < bd) { bd = d; best = &m; }
            else if (m.t < s) break; // marks are time-ordered; going further back only gets worse
        }
        return best;
    }

    // interpolated correction state at time t
    Hist histAt(double t) const {
        if (histCount_ == 0) return Hist{0, false, 0, 0, 1};
        const Hist& newest = hist_[size_t(histHead_)];
        if (t >= double(newest.t)) return newest;
        for (int k = 1; k < histCount_; ++k) {
            const Hist& a = hist_[size_t((histHead_ - k + kHist) % kHist)];
            if (double(a.t) <= t) {
                const Hist& b = hist_[size_t((histHead_ - k + 1 + kHist) % kHist)];
                if (!a.voiced || !b.voiced) return (t - a.t < b.t - t) ? a : b;
                const double u = (t - double(a.t)) / std::max(1.0, double(b.t - a.t));
                Hist h = b;
                h.shift = float(a.shift + u * (b.shift - a.shift));
                h.formant = float(a.formant + u * (b.formant - a.formant));
                h.gain = float(a.gain + u * (b.gain - a.gain));
                return h;
            }
        }
        return hist_[size_t((histHead_ - histCount_ + 1 + kHist) % kHist)];
    }

    // --- synthesis -------------------------------------------------------------
    void synthesise() {
        const double s = synth_;
        const Hist h = histAt(s);
        const Mark* mk = h.voiced ? nearestMark(s) : nullptr;
        if (mk && std::fabs(mk->t - s) <= 2.5 * mk->period) {
            const double Pa = mk->period;
            const double ratio = std::pow(2.0, double(h.shift) / 12.0);
            const bool classic = params_.classicMode;
            double rf;
            if (params_.formantCorrection && !classic) {
                rf = (100.0 / std::clamp(double(params_.throatLength), 50.0, 180.0))
                     * std::pow(2.0, double(h.formant) / 12.0);
            } else {
                rf = ratio;
            }
            rf = std::clamp(rf, 0.25, 4.0);
            const double Ps = Pa / ratio;
            const double L = std::min(Pa / rf, pMax_);
            const double g = std::min(1.0, Ps / L) * h.gain;
            addGrain(s, mk->t, L, rf, float(g));
            synth_ = s + std::clamp(Ps, 8.0, pMax_);
        } else {
            addGrain(s, s, unvHop_, 1.0, 1.f);
            synth_ = s + unvHop_;
        }
    }

    // overlap-add one grain centred on output time s, read around input time a
    void addGrain(double s, double a, double L, double rf, float gain) {
        const int mask = ringSize_ - 1;
        const int64_t j0 = int64_t(std::ceil(s - L));
        const int64_t j1 = int64_t(std::floor(s + L));
        const double wScale = (kHann - 1) * 0.5 / L;
        for (int64_t j = j0; j <= j1; ++j) {
            const double u = double(j) - s;           // -L..L
            const double wi = (u + L) * wScale;
            const int wi0 = int(wi);
            const float wf = float(wi - wi0);
            const float win = gain * (hann_[size_t(wi0)] + wf * (hann_[size_t(wi0 + 1)] - hann_[size_t(wi0)]));
            const double pos = a + u * rf;
            const int64_t p0 = int64_t(std::floor(pos));
            const float f = float(pos - double(p0));
            const size_t oj = size_t(j & mask);
            for (int c = 0; c < numCh_; ++c) {
                const float* x = in_[size_t(c)].data();
                // 4-point Hermite interpolation
                const float xm1 = x[size_t((p0 - 1) & mask)], x0 = x[size_t(p0 & mask)];
                const float x1 = x[size_t((p0 + 1) & mask)], x2 = x[size_t((p0 + 2) & mask)];
                const float c1 = 0.5f * (x1 - xm1);
                const float c2 = xm1 - 2.5f * x0 + 2.f * x1 - 0.5f * x2;
                const float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
                const float v = ((c3 * f + c2) * f + c1) * f + x0;
                ola_[size_t(c)][oj] += win * v;
            }
        }
    }

    static constexpr int kHann = 2048;
    static constexpr int kMarks = 64;
    static constexpr int kHist = 256;

    double fs_ = 44100;
    int numCh_ = 1;
    Params params_;
    InputType preparedInput_ = InputType::AltoTenor;
    bool preparedLowLatency_ = false;

    PitchDetector detector_;
    PitchCorrector corrector_;

    double pMax_ = 551, unvHop_ = 132, lookahead_ = 800;
    int latency_ = 1400, ringSize_ = 8192;
    std::array<std::vector<float>, kMaxChannels> in_, ola_;
    std::vector<float> mono_;
    std::array<float, kHann + 1> hann_{};

    int64_t now_ = 0;
    double synth_ = 0;
    std::array<Mark, kMarks> marks_{};
    int marksHead_ = 0, marksCount_ = 0;
    double lastMark_ = -1e18;
    std::array<Hist, kHist> hist_{};
    int histHead_ = 0, histCount_ = 0;
    bool voicedNow_ = false;
    double curPeriod_ = 0;

    std::atomic<float> inHz_{0}, outHz_{0}, tgtMidi_{-1}, corrCents_{0};
};

} // namespace lighttune
