// LightTune - lightweight real-time pitch correction
// Scales.h : keys, scales and per-note state (On / Remove / Bypass)
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace lighttune {

constexpr double kPi = 3.14159265358979323846;

// State of one pitch class, same semantics as the classic "Remove" / "Bypass"
// buttons found on hardware/software pitch correctors:
//   Off    : the note is removed from the scale (never used as a target)
//   On     : the note is a valid target
//   Bypass : when the singer is closest to this note, it is left uncorrected
enum class NoteState : uint8_t { Off = 0, On = 1, Bypass = 2 };

enum class Scale : int {
    Chromatic = 0,
    Major,
    Minor,            // natural minor / aeolian
    HarmonicMinor,
    MelodicMinor,
    Dorian,
    Phrygian,
    Lydian,
    Mixolydian,
    Locrian,
    MajorPentatonic,
    MinorPentatonic,
    Blues,
    WholeTone,
    PhrygianDominant,
    HungarianMinor,
    Count
};

inline const char* scaleName(Scale s) {
    static const char* names[] = {
        "Chromatic", "Major", "Minor", "Harmonic Minor", "Melodic Minor",
        "Dorian", "Phrygian", "Lydian", "Mixolydian", "Locrian",
        "Major Pentatonic", "Minor Pentatonic", "Blues", "Whole Tone",
        "Phrygian Dominant", "Hungarian Minor"};
    int i = static_cast<int>(s);
    return (i >= 0 && i < static_cast<int>(Scale::Count)) ? names[i] : "?";
}

inline const char* keyName(int key) {
    static const char* names[] = {"C", "C#", "D", "D#", "E", "F",
                                  "F#", "G", "G#", "A", "A#", "B"};
    return names[((key % 12) + 12) % 12];
}

using NoteMask = std::array<NoteState, 12>;

// Builds the 12 pitch-class mask (index 0 = C) for a key + scale.
inline NoteMask makeScaleMask(int key, Scale scale) {
    // bit i set = interval of i semitones above the key is in the scale
    static const uint16_t kMasks[] = {
        0xFFF, // Chromatic
        0xAB5, // Major
        0x5AD, // Minor
        0x9AD, // HarmonicMinor
        0xAAD, // MelodicMinor
        0x6AD, // Dorian
        0x5AB, // Phrygian
        0xAD5, // Lydian
        0x6B5, // Mixolydian
        0x56B, // Locrian
        0x295, // MajorPentatonic
        0x4A9, // MinorPentatonic
        0x4E9, // Blues
        0x555, // WholeTone
        0x5B3, // PhrygianDominant
        0x9CD, // HungarianMinor
    };
    const int si = static_cast<int>(scale);
    const uint16_t bits = (si >= 0 && si < static_cast<int>(Scale::Count)) ? kMasks[si] : 0xFFF;
    NoteMask m;
    m.fill(NoteState::Off);
    for (int iv = 0; iv < 12; ++iv)
        if (bits & (1u << iv)) m[static_cast<size_t>(((key + iv) % 12 + 12) % 12)] = NoteState::On;
    return m;
}

} // namespace lighttune
