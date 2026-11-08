// LightTune - lightweight real-time pitch correction
// PitchCorrector.h : decides *how much* to shift, every analysis hop.
//
// Implements the behaviour of the usual "Auto mode" controls:
//   Key / Scale / per-note Remove & Bypass / MIDI target notes
//   Retune Speed, Humanize, Flex-Tune, Natural Vibrato
//   Transpose, Detune (reference pitch), Throat length, Formant on/off
//   Create Vibrato (shape, rate, onset delay, onset rate, variation,
//                   pitch / amplitude / formant amounts)
//   Classic mode (old-school slew-limited retune, no formant/throat/transpose)
#pragma once

#include "Scales.h"

#include <algorithm>
#include <bitset>
#include <cmath>
#include <cstdint>

namespace lighttune {

enum class InputType : int { Soprano = 0, AltoTenor, LowMale, Instrument, BassInstrument, Count };
enum class VibratoShape : int { Sine = 0, Square, Sawtooth, Count };

inline const char* inputTypeName(InputType t) {
    static const char* n[] = {"Soprano", "Alto/Tenor", "Low Male", "Instrument", "Bass Instrument"};
    int i = int(t);
    return (i >= 0 && i < int(InputType::Count)) ? n[i] : "?";
}

// frequency range searched by the detector for each input type (Hz)
inline void inputTypeRange(InputType t, double& lo, double& hi) {
    switch (t) {
        case InputType::Soprano:        lo = 110; hi = 1200; break;
        case InputType::AltoTenor:      lo = 80;  hi = 900;  break;
        case InputType::LowMale:        lo = 55;  hi = 500;  break;
        case InputType::Instrument:     lo = 60;  hi = 1600; break;
        case InputType::BassInstrument: lo = 32;  hi = 400;  break;
        default:                        lo = 80;  hi = 900;  break;
    }
}

struct Params {
    // --- scale -----------------------------------------------------------
    int key = 0;                       // 0 = C ... 11 = B
    Scale scale = Scale::Chromatic;
    NoteMask notes = makeScaleMask(0, Scale::Chromatic); // filled from key/scale, user may edit
    bool midiTargetNotes = false;      // use held MIDI notes as target pitch classes

    // --- correction ------------------------------------------------------
    float retuneSpeedMs = 20.f;        // 0 (instant / "hard tune") .. 400
    float humanize = 0.f;              // 0..100  slows retune on sustained notes
    float flexTune = 0.f;              // 0..100  only correct when close to the target
    float naturalVibratoDb = 0.f;      // -12..+12 dB  scale the singer's own vibrato
    float tracking = 50.f;             // 0..100  choosy .. relaxed
    InputType inputType = InputType::AltoTenor;

    // --- pitch / formant ---------------------------------------------------
    float transpose = 0.f;             // semitones -24..+24
    float detuneCents = 0.f;           // -100..+100 (A4 reference = 440 * 2^(c/1200))
    bool formantCorrection = true;     // keep formants when shifting
    float throatLength = 100.f;        // 50..180 % (needs formant correction)

    // --- created vibrato -----------------------------------------------------
    VibratoShape vibShape = VibratoShape::Sine;
    float vibRateHz = 5.5f;            // 0.1..12
    float vibOnsetDelayMs = 300.f;     // 0..2000
    float vibOnsetRateMs = 300.f;      // 0..2000
    float vibVariation = 0.f;          // 0..100 %
    float vibPitchCents = 0.f;         // 0..200 cents
    float vibAmplitudePct = 0.f;       // 0..100 %
    float vibFormantPct = 0.f;         // 0..100 %

    // --- engine --------------------------------------------------------------
    bool classicMode = false;
    bool lowLatency = false;
    float mix = 100.f;                 // dry/wet %

    void applyKeyScale() { notes = makeScaleMask(key, scale); }
};

// Per-hop output of the corrector
struct Correction {
    float shiftSemis = 0.f;   // total pitch shift applied to the voice
    float formantSemis = 0.f; // formant shift (only used with formant correction)
    float gain = 1.f;         // amplitude vibrato
    float targetMidi = -1.f;  // target note (for display)
};

class PitchCorrector {
public:
    void prepare(double hopSeconds) {
        dt_ = hopSeconds;
        reset();
    }
    void reset() {
        applied_ = 0;
        target_ = -1000;
        hold_ = 0;
        noteTime_ = 0;
        wasVoiced_ = false;
        c1_ = c2_ = 0;
        lfoPhase_ = 0;
        rnd_ = 0x1234567u;
        varA_ = varB_ = 0;
    }

    void setMidiNotes(const std::bitset<128>& held) { midiHeld_ = held; }

    // inputMidi : fractional MIDI note of the input (A4 = 69 @ 440 Hz)
    Correction process(bool voiced, double inputMidi, const Params& p) {
        Correction out;
        if (!voiced || !(inputMidi > 0)) {
            wasVoiced_ = false;
            hold_ = 0;
            out.gain = 1.f;
            return out;
        }

        const bool classic = p.classicMode;
        const double ref = p.detuneCents / 100.0; // detune shifts the whole grid

        // ---- natural vibrato: split the contour into a slow centre + vibrato
        if (!wasVoiced_) { c1_ = c2_ = inputMidi; applied_ = 0; noteTime_ = 0; target_ = -1000; }
        {
            const double a = 1.0 - std::exp(-2.0 * kPi * 2.5 * dt_); // 2-pole, 2.5 Hz
            c1_ += a * (inputMidi - c1_);
            c2_ += a * (c1_ - c2_);
        }
        const double centre = c2_;
        double x = inputMidi;
        if (!classic && std::fabs(p.naturalVibratoDb) > 0.01f) {
            const double g = std::pow(10.0, p.naturalVibratoDb / 20.0);
            x = centre + (inputMidi - centre) * g;
        }

        // ---- target note selection (choice made on the vibrato-free centre)
        const double pick = (std::fabs(inputMidi - centre) < 1.0) ? centre : inputMidi;
        bool bypass = false;
        const double tgt = chooseTarget(pick - ref, p, bypass) + ref;
        if (std::fabs(tgt - target_) > 1e-6) { target_ = tgt; hold_ = 0; }
        else hold_ += dt_;

        double want = bypass ? 0.0 : (tgt - x);

        // ---- Flex-Tune: correct only when the singer is close to the note
        if (!classic && p.flexTune > 0.f && !bypass) {
            const double f = p.flexTune / 100.0;
            const double zone = 0.5 * (1.0 - f) + 0.03;
            const double dev = std::fabs(want);
            if (dev > zone) {
                const double u = std::clamp((dev - zone) / 0.2, 0.0, 1.0);
                want *= 1.0 - u * u * (3 - 2 * u);
            }
        }

        // ---- retune speed (+ humanize)
        double retune = std::clamp(p.retuneSpeedMs, 0.f, 400.f) / 1000.0;
        if (!classic && p.humanize > 0.f) {
            const double sustain = std::clamp((hold_ - 0.12) / 0.3, 0.0, 1.0);
            retune += (p.humanize / 100.0) * 0.4 * sustain;
        }
        if (classic) {
            // old-school behaviour: constant slew rate (semitone per retune time)
            if (retune <= 0.0) applied_ = want;
            else {
                const double step = dt_ / retune * 1.5;
                applied_ += std::clamp(want - applied_, -step, step);
            }
        } else {
            if (retune <= 0.0) applied_ = want;
            else applied_ += (1.0 - std::exp(-dt_ * 3.0 / retune)) * (want - applied_);
        }

        double shift = (x - inputMidi) + applied_;
        if (!classic) shift += p.transpose;

        // ---- created vibrato
        noteTime_ += dt_;
        double formant = 0.0;
        out.gain = 1.f;
        if (p.vibPitchCents > 0.f || p.vibAmplitudePct > 0.f || p.vibFormantPct > 0.f) {
            const double delay = p.vibOnsetDelayMs / 1000.0;
            const double rise = std::max(1e-3, p.vibOnsetRateMs / 1000.0);
            const double env = std::clamp((noteTime_ - delay) / rise, 0.0, 1.0);
            // smooth random variation of rate & depth
            const double v = p.vibVariation / 100.0;
            varA_ += 0.05 * (noise() - varA_);
            varB_ += 0.05 * (noise() - varB_);
            const double rate = p.vibRateHz * (1.0 + 0.3 * v * varA_);
            const double depthMod = 1.0 + 0.5 * v * varB_;
            lfoPhase_ += rate * dt_;
            lfoPhase_ -= std::floor(lfoPhase_);
            double l;
            switch (p.vibShape) {
                case VibratoShape::Square:   l = std::tanh(4.0 * std::sin(2 * kPi * lfoPhase_)) / std::tanh(4.0); break;
                case VibratoShape::Sawtooth: l = 2.0 * lfoPhase_ - 1.0; break;
                default:                     l = std::sin(2 * kPi * lfoPhase_); break;
            }
            l *= env * depthMod;
            shift += l * p.vibPitchCents / 100.0;
            out.gain = float(std::pow(10.0, (l * p.vibAmplitudePct / 100.0 * 6.0) / 20.0));
            formant = l * p.vibFormantPct / 100.0;
        }

        out.shiftSemis = float(shift);
        out.formantSemis = float(formant);
        out.targetMidi = bypass ? float(inputMidi) : float(tgt + (classic ? 0 : p.transpose));
        wasVoiced_ = true;
        return out;
    }

private:
    double chooseTarget(double x, const Params& p, bool& bypass) {
        // allowed pitch classes
        bool allowed[12];
        bool any = false;
        bool useMidi = p.midiTargetNotes && midiHeld_.any();
        for (int pc = 0; pc < 12; ++pc) {
            if (useMidi) {
                bool h = false;
                for (int n = pc; n < 128; n += 12) h = h || midiHeld_[size_t(n)];
                allowed[pc] = h;
            } else {
                allowed[pc] = p.notes[size_t(pc)] != NoteState::Off;
            }
            any = any || allowed[pc];
        }
        if (!any) { bypass = true; return x; }

        const int base = int(std::floor(x));
        double best = 0, bestD = 1e9;
        for (int m = base - 12; m <= base + 12; ++m) {
            if (!allowed[((m % 12) + 12) % 12]) continue;
            double d = std::fabs(x - m);
            if (std::fabs(m - (target_ - p.detuneCents / 100.0)) < 1e-6) d -= 0.06; // hysteresis
            if (d < bestD) { bestD = d; best = m; }
        }
        const int pc = ((int(best) % 12) + 12) % 12;
        bypass = !useMidi && p.notes[size_t(pc)] == NoteState::Bypass;
        return best;
    }

    double noise() {
        rnd_ = rnd_ * 1664525u + 1013904223u;
        return double(rnd_ >> 8) / double(1u << 24) * 2.0 - 1.0;
    }

    double dt_ = 128.0 / 44100.0;
    double applied_ = 0, target_ = -1000, hold_ = 0, noteTime_ = 0;
    double c1_ = 0, c2_ = 0;
    double lfoPhase_ = 0, varA_ = 0, varB_ = 0;
    uint32_t rnd_ = 0x1234567u;
    bool wasVoiced_ = false;
    std::bitset<128> midiHeld_;
};

} // namespace lighttune
