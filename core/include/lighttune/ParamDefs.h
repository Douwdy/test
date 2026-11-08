// LightTune - lightweight real-time pitch correction
// ParamDefs.h : one parameter table shared by every plugin wrapper (CLAP, JUCE...)
#pragma once

#include "PitchCorrector.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>

namespace lighttune {

enum ParamId : int {
    kKey = 0, kScale, kRetune, kHumanize, kFlexTune, kNaturalVibrato, kTracking, kInputType,
    kTranspose, kDetune, kFormant, kThroat, kClassic, kLowLatency,
    kVibShape, kVibRate, kVibDelay, kVibOnset, kVibVariation, kVibPitch, kVibAmp, kVibFormant,
    kMix, kMidiTarget,
    kNoteC, kNoteCs, kNoteD, kNoteDs, kNoteE, kNoteF, kNoteFs, kNoteG, kNoteGs, kNoteA, kNoteAs, kNoteB,
    kNumParams
};

struct ParamDef {
    const char* id;      // stable string id (state / automation)
    const char* name;    // display name
    const char* module;  // group
    float min, max, def;
    bool stepped;
    const char* unit;
};

inline const std::array<ParamDef, kNumParams>& paramDefs() {
    static const std::array<ParamDef, kNumParams> d = {{
        {"key", "Key", "Scale", 0, 11, 0, true, ""},
        {"scale", "Scale", "Scale", 0, float(int(Scale::Count) - 1), 0, true, ""},
        {"retune", "Retune Speed", "Correction", 0, 400, 20, false, "ms"},
        {"humanize", "Humanize", "Correction", 0, 100, 0, false, ""},
        {"flextune", "Flex-Tune", "Correction", 0, 100, 0, false, ""},
        {"natvib", "Natural Vibrato", "Correction", -12, 12, 0, false, "dB"},
        {"tracking", "Tracking", "Correction", 0, 100, 50, false, ""},
        {"inputtype", "Input Type", "Correction", 0, float(int(InputType::Count) - 1), 1, true, ""},
        {"transpose", "Transpose", "Pitch", -24, 24, 0, true, "st"},
        {"detune", "Detune", "Pitch", -100, 100, 0, false, "cents"},
        {"formant", "Formant", "Pitch", 0, 1, 1, true, ""},
        {"throat", "Throat Length", "Pitch", 50, 180, 100, false, "%"},
        {"classic", "Classic Mode", "Engine", 0, 1, 0, true, ""},
        {"lowlatency", "Low Latency", "Engine", 0, 1, 0, true, ""},
        {"vibshape", "Vibrato Shape", "Vibrato", 0, float(int(VibratoShape::Count) - 1), 0, true, ""},
        {"vibrate", "Vibrato Rate", "Vibrato", 0.1f, 12, 5.5f, false, "Hz"},
        {"vibdelay", "Vibrato Onset Delay", "Vibrato", 0, 2000, 300, false, "ms"},
        {"vibonset", "Vibrato Onset Rate", "Vibrato", 0, 2000, 300, false, "ms"},
        {"vibvar", "Vibrato Variation", "Vibrato", 0, 100, 0, false, "%"},
        {"vibpitch", "Vibrato Pitch Amount", "Vibrato", 0, 200, 0, false, "cents"},
        {"vibamp", "Vibrato Amplitude Amount", "Vibrato", 0, 100, 0, false, "%"},
        {"vibformant", "Vibrato Formant Amount", "Vibrato", 0, 100, 0, false, "%"},
        {"mix", "Mix", "Engine", 0, 100, 100, false, "%"},
        {"miditarget", "MIDI Target Notes", "Scale", 0, 1, 0, true, ""},
        {"note_c", "Note C", "Notes", 0, 2, 0, true, ""},
        {"note_cs", "Note C#", "Notes", 0, 2, 0, true, ""},
        {"note_d", "Note D", "Notes", 0, 2, 0, true, ""},
        {"note_ds", "Note D#", "Notes", 0, 2, 0, true, ""},
        {"note_e", "Note E", "Notes", 0, 2, 0, true, ""},
        {"note_f", "Note F", "Notes", 0, 2, 0, true, ""},
        {"note_fs", "Note F#", "Notes", 0, 2, 0, true, ""},
        {"note_g", "Note G", "Notes", 0, 2, 0, true, ""},
        {"note_gs", "Note G#", "Notes", 0, 2, 0, true, ""},
        {"note_a", "Note A", "Notes", 0, 2, 0, true, ""},
        {"note_as", "Note A#", "Notes", 0, 2, 0, true, ""},
        {"note_b", "Note B", "Notes", 0, 2, 0, true, ""},
    }};
    return d;
}

// Note override parameter values
//   0 = follow the scale, 1 = Remove (never a target), 2 = Bypass (left uncorrected)
inline const char* noteOverrideName(int v) {
    static const char* n[] = {"Scale", "Remove", "Bypass"};
    return (v >= 0 && v <= 2) ? n[v] : "?";
}

// Human readable value (for hosts' generic editors)
inline void formatParam(int id, double v, char* out, int len) {
    const auto& d = paramDefs()[size_t(id)];
    const int iv = int(std::lround(v));
    switch (id) {
        case kKey: std::snprintf(out, size_t(len), "%s", keyName(iv)); return;
        case kScale: std::snprintf(out, size_t(len), "%s", scaleName(Scale(iv))); return;
        case kInputType: std::snprintf(out, size_t(len), "%s", inputTypeName(InputType(iv))); return;
        case kFormant: case kClassic: case kLowLatency: case kMidiTarget:
            std::snprintf(out, size_t(len), "%s", iv ? "On" : "Off"); return;
        case kVibShape: { static const char* s[] = {"Sine", "Square", "Sawtooth"}; std::snprintf(out, size_t(len), "%s", s[std::clamp(iv, 0, 2)]); return; }
        default: break;
    }
    if (id >= kNoteC && id <= kNoteB) { std::snprintf(out, size_t(len), "%s", noteOverrideName(iv)); return; }
    if (d.stepped) std::snprintf(out, size_t(len), "%d %s", iv, d.unit);
    else std::snprintf(out, size_t(len), "%.1f %s", v, d.unit);
}

// Build engine parameters from a flat array of values
inline Params paramsFromValues(const float* v) {
    Params p;
    auto I = [&](int id) { return int(std::lround(v[id])); };
    p.key = std::clamp(I(kKey), 0, 11);
    p.scale = Scale(std::clamp(I(kScale), 0, int(Scale::Count) - 1));
    p.applyKeyScale();
    for (int n = 0; n < 12; ++n) {
        const int o = I(kNoteC + n);
        if (o == 1) p.notes[size_t(n)] = NoteState::Off;
        else if (o == 2) p.notes[size_t(n)] = NoteState::Bypass;
    }
    p.midiTargetNotes = I(kMidiTarget) != 0;
    p.retuneSpeedMs = v[kRetune];
    p.humanize = v[kHumanize];
    p.flexTune = v[kFlexTune];
    p.naturalVibratoDb = v[kNaturalVibrato];
    p.tracking = v[kTracking];
    p.inputType = InputType(std::clamp(I(kInputType), 0, int(InputType::Count) - 1));
    p.transpose = float(I(kTranspose));
    p.detuneCents = v[kDetune];
    p.formantCorrection = I(kFormant) != 0;
    p.throatLength = v[kThroat];
    p.classicMode = I(kClassic) != 0;
    p.lowLatency = I(kLowLatency) != 0;
    p.vibShape = VibratoShape(std::clamp(I(kVibShape), 0, int(VibratoShape::Count) - 1));
    p.vibRateHz = v[kVibRate];
    p.vibOnsetDelayMs = v[kVibDelay];
    p.vibOnsetRateMs = v[kVibOnset];
    p.vibVariation = v[kVibVariation];
    p.vibPitchCents = v[kVibPitch];
    p.vibAmplitudePct = v[kVibAmp];
    p.vibFormantPct = v[kVibFormant];
    p.mix = v[kMix];
    return p;
}

} // namespace lighttune
