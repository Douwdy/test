// LightTune - lightweight real-time pitch correction
// PitchDetector.h : low-cost monophonic pitch tracker.
//
// Design (see docs/RESEARCH.md for the background):
//  * The signal is low-passed and decimated to ~11 kHz. Vocal fundamentals
//    live below ~1.2 kHz, so this loses nothing for detection and divides the
//    cost by the decimation factor.
//  * The squared-difference function d(tau) = sum (x[j] - x[j - tau])^2 is
//    maintained *incrementally*: every new sample adds one term and removes the
//    oldest one for every lag. This is the same trick as the original 1998
//    autocorrelation patent (update the running correlation instead of
//    recomputing it) and costs O(maxLag) per decimated sample instead of
//    O(window * maxLag) per analysis frame.
//  * Every hop the cumulative-mean-normalised difference (YIN) is evaluated,
//    the first dip under the threshold is taken, parabolic interpolation gives
//    sub-sample lag, then the lag is refined on the full-rate signal around
//    the coarse estimate so the final precision is well below 1 cent.
#pragma once

#include "Scales.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace lighttune {

struct PitchEstimate {
    bool voiced = false;
    double period = 0.0;      // in full-rate samples
    double frequency = 0.0;   // Hz
    float aperiodicity = 1.f; // 0 = perfectly periodic, 1 = noise
    float levelDb = -120.f;   // level of the analysed (low-passed) signal
    int64_t centerTime = 0;   // absolute full-rate sample index the estimate describes
};

// Simple RBJ biquad (transposed direct form II)
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1 = 0, z2 = 0;
    void lowpass(double fs, double fc, double q) {
        const double w = 2.0 * kPi * fc / fs;
        const double c = std::cos(w), s = std::sin(w), alpha = s / (2.0 * q);
        const double a0 = 1.0 + alpha;
        b0 = float((1.0 - c) * 0.5 / a0);
        b1 = float((1.0 - c) / a0);
        b2 = b0;
        a1 = float(-2.0 * c / a0);
        a2 = float((1.0 - alpha) / a0);
        z1 = z2 = 0;
    }
    inline float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

class PitchDetector {
public:
    void prepare(double sampleRate, double minHz, double maxHz) {
        fs_ = sampleRate;
        decim_ = std::max(1, int(std::lround(fs_ / 11025.0)));
        fsd_ = fs_ / decim_;
        minHz_ = minHz;
        maxHz_ = maxHz;

        // 4th-order Butterworth low-pass (two biquads), anti-alias + formant attenuation.
        const double fc = std::min(std::max(1.4 * maxHz, 1000.0), 0.42 * fsd_);
        lp1_.lowpass(fs_, fc, 0.5411961);
        lp2_.lowpass(fs_, fc, 1.3065630);

        minLag_ = std::max(2, int(std::floor(fsd_ / maxHz)));
        maxLag_ = int(std::ceil(fsd_ / minHz)) + 2;
        win_ = maxLag_;

        dSize_ = 1;
        while (dSize_ < win_ + maxLag_ + 8) dSize_ <<= 1;
        dbuf_.assign(size_t(dSize_) * 2, 0.f);
        d_.assign(size_t(maxLag_) + 2, 0.0);
        cmnd_.assign(size_t(maxLag_) + 2, 1.f);

        fSize_ = 1;
        while (fSize_ < int(maxLag_ * decim_ * 3 + 64)) fSize_ <<= 1;
        fbuf_.assign(size_t(fSize_), 0.f);

        hopD_ = 32; // analysis every 32 decimated samples (~2.9 ms)
        reset();
    }

    void reset() {
        std::fill(dbuf_.begin(), dbuf_.end(), 0.f);
        std::fill(d_.begin(), d_.end(), 0.0);
        std::fill(fbuf_.begin(), fbuf_.end(), 0.f);
        lp1_.z1 = lp1_.z2 = lp2_.z1 = lp2_.z2 = 0;
        energy_ = 0;
        dPos_ = 0;
        dCount_ = 0;
        decimPhase_ = 0;
        fullTime_ = 0;
        prevLag_ = 0;
        last_ = PitchEstimate{};
    }

    // "Tracking" 0..100 : low = choosy (only clean periodic input is treated
    // as voiced), high = relaxed (rough / breathy voices still tracked).
    void setTracking(float t) {
        t = std::clamp(t, 0.f, 100.f) / 100.f;
        dipThreshold_ = 0.10f + 0.15f * t;
        voicedThreshold_ = 0.20f + 0.30f * t;
    }
    void setGateDb(float g) { gateDb_ = g; }

    int hopSize() const { return hopD_ * decim_; }
    int decimation() const { return decim_; }
    double maxPeriodSamples() const { return fs_ / minHz_; }

    // Push one full-rate sample. Returns true when a new estimate is available.
    inline bool push(float x) {
        const float y = lp2_.process(lp1_.process(x));
        fbuf_[size_t(fullTime_ & (fSize_ - 1))] = y;
        ++fullTime_;
        if (++decimPhase_ < decim_) return false;
        decimPhase_ = 0;
        pushDecimated(y);
        if ((dCount_ % hopD_) == 0) {
            analyse();
            return true;
        }
        return false;
    }

    const PitchEstimate& estimate() const { return last_; }

private:
    inline void pushDecimated(float x) {
        // mirrored circular buffer: every sample is stored twice so that the
        // lag loop reads a contiguous block (vectorisable, no masking).
        dPos_ = (dPos_ + 1) & (dSize_ - 1);
        float* buf = dbuf_.data();
        buf[dPos_] = x;
        buf[dPos_ + dSize_] = x;
        const float* cur = buf + dPos_ + dSize_;   // cur[-k] = x[n-k]
        const float* old = cur - win_;             // x[n-W]
        const float xo = old[0];
        double* d = d_.data();
        for (int tau = 1; tau <= maxLag_; ++tau) {
            const float a = x - cur[-tau];
            const float b = xo - old[-tau];
            d[tau] += double(a * a - b * b);
        }
        energy_ += double(x) * x - double(xo) * xo;
        ++dCount_;
        if ((dCount_ & 2047) == 0) recompute(); // kill accumulated rounding drift
    }

    void recompute() {
        const float* cur = dbuf_.data() + dPos_ + dSize_;
        for (int tau = 1; tau <= maxLag_; ++tau) {
            double s = 0;
            for (int j = 0; j < win_; ++j) {
                const float a = cur[-j] - cur[-j - tau];
                s += double(a) * a;
            }
            d_[size_t(tau)] = s;
        }
        double e = 0;
        for (int j = 0; j < win_; ++j) e += double(cur[-j]) * cur[-j];
        energy_ = e;
    }

    void analyse() {
        PitchEstimate est;
        const double rms = std::sqrt(std::max(energy_, 0.0) / win_);
        est.levelDb = float(20.0 * std::log10(rms + 1e-12));

        // cumulative mean normalised difference
        double run = 0;
        cmnd_[0] = 1.f;
        for (int tau = 1; tau <= maxLag_; ++tau) {
            run += d_[size_t(tau)];
            cmnd_[size_t(tau)] = run > 1e-20 ? float(d_[size_t(tau)] * tau / run) : 1.f;
        }

        int best = -1;
        for (int tau = minLag_; tau < maxLag_; ++tau) {
            if (cmnd_[size_t(tau)] < dipThreshold_) {
                while (tau + 1 < maxLag_ && cmnd_[size_t(tau + 1)] < cmnd_[size_t(tau)]) ++tau;
                best = tau;
                break;
            }
        }
        if (best < 0) {
            float mv = 2.f;
            for (int tau = minLag_; tau < maxLag_; ++tau)
                if (cmnd_[size_t(tau)] < mv) { mv = cmnd_[size_t(tau)]; best = tau; }
        }

        // octave-jump guard: stay on the previous period if it is almost as good
        if (prevLag_ > 0 && best > 0) {
            const double ratio = double(best) / prevLag_;
            if (std::fabs(std::log2(ratio)) > 0.75) {
                int p = int(std::lround(prevLag_));
                int lo = std::max(minLag_, p - 2), hi = std::min(maxLag_ - 1, p + 2);
                int pb = -1; float pv = 2.f;
                for (int t = lo; t <= hi; ++t)
                    if (cmnd_[size_t(t)] < pv) { pv = cmnd_[size_t(t)]; pb = t; }
                if (pb > 0 && pv < voicedThreshold_ && pv < cmnd_[size_t(best)] + 0.06f) best = pb;
            }
        }

        double lag = best;
        if (best > 1 && best < maxLag_) {
            const double a = cmnd_[size_t(best - 1)], b = cmnd_[size_t(best)], c = cmnd_[size_t(best + 1)];
            const double den = a - 2 * b + c;
            if (den > 1e-12) lag += std::clamp(0.5 * (a - c) / den, -0.5, 0.5);
        }
        est.aperiodicity = cmnd_[size_t(best)];
        est.voiced = est.aperiodicity < voicedThreshold_ && est.levelDb > gateDb_;

        double period = lag * decim_;
        if (est.voiced) period = refine(period);
        est.period = period;
        est.frequency = period > 0 ? fs_ / period : 0.0;
        if (est.frequency < minHz_ * 0.9 || est.frequency > maxHz_ * 1.1) est.voiced = false;
        // the full-rate refinement looks at the last ~2 periods, so the estimate
        // describes the signal about one period ago
        est.centerTime = est.voiced && decim_ > 1 ? fullTime_ - int64_t(period)
                                                  : fullTime_ - int64_t(0.5 * (win_ + lag) * decim_);
        prevLag_ = est.voiced ? lag : 0.0;
        last_ = est;
    }

    // full-rate refinement of the period around the coarse estimate
    double refine(double p0) const {
        if (decim_ == 1) return p0;
        const int n = int(std::ceil(p0));
        const int lo = std::max(2, int(std::floor(p0)) - decim_);
        const int hi = int(std::ceil(p0)) + decim_;
        if (hi + n + 2 >= fSize_) return p0;
        const int mask = fSize_ - 1;
        const int64_t end = fullTime_ - 1;
        double vals[64];
        const int cnt = std::min(hi - lo + 1, 64);
        for (int k = 0; k < cnt; ++k) {
            const int L = lo + k;
            double s = 0;
            for (int j = 0; j < n; ++j) {
                const float a = fbuf_[size_t((end - j) & mask)] - fbuf_[size_t((end - j - L) & mask)];
                s += double(a) * a;
            }
            vals[k] = s;
        }
        int bk = 0;
        for (int k = 1; k < cnt; ++k) if (vals[k] < vals[bk]) bk = k;
        double lag = lo + bk;
        if (bk > 0 && bk < cnt - 1) {
            const double a = vals[bk - 1], b = vals[bk], c = vals[bk + 1];
            const double den = a - 2 * b + c;
            if (den > 1e-20) lag += std::clamp(0.5 * (a - c) / den, -0.5, 0.5);
        }
        return lag;
    }

    double fs_ = 44100, fsd_ = 11025, minHz_ = 60, maxHz_ = 1000;
    int decim_ = 4, minLag_ = 10, maxLag_ = 200, win_ = 200, hopD_ = 32;
    int dSize_ = 512, dPos_ = 0, fSize_ = 4096, decimPhase_ = 0;
    int64_t dCount_ = 0, fullTime_ = 0;
    double energy_ = 0, prevLag_ = 0;
    float dipThreshold_ = 0.15f, voicedThreshold_ = 0.35f, gateDb_ = -60.f;
    Biquad lp1_, lp2_;
    std::vector<float> dbuf_, fbuf_, cmnd_;
    std::vector<double> d_;
    PitchEstimate last_;
};

} // namespace lighttune
