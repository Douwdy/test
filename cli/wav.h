// Minimal WAV reader/writer (PCM 16/24/32-bit, float32), no dependencies.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace wav {

struct Audio {
    int sampleRate = 44100;
    int channels = 1;
    std::vector<std::vector<float>> data; // [channel][frame]
    size_t frames() const { return data.empty() ? 0 : data[0].size(); }
};

inline bool read(const std::string& path, Audio& a, std::string& err) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) { err = "cannot open " + path; return false; }
    std::vector<uint8_t> b;
    {
        uint8_t tmp[65536];
        size_t n;
        while ((n = std::fread(tmp, 1, sizeof tmp, f)) > 0) b.insert(b.end(), tmp, tmp + n);
        std::fclose(f);
    }
    auto u16 = [&](size_t o) { return uint32_t(b[o] | (b[o + 1] << 8)); };
    auto u32 = [&](size_t o) { return uint32_t(b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (uint32_t(b[o + 3]) << 24)); };
    if (b.size() < 12 || std::memcmp(b.data(), "RIFF", 4) || std::memcmp(b.data() + 8, "WAVE", 4)) {
        err = "not a RIFF/WAVE file"; return false;
    }
    int fmt = 0, bits = 0;
    size_t pos = 12, dataOff = 0, dataLen = 0;
    while (pos + 8 <= b.size()) {
        const uint32_t len = u32(pos + 4);
        if (!std::memcmp(b.data() + pos, "fmt ", 4)) {
            fmt = int(u16(pos + 8));
            a.channels = int(u16(pos + 10));
            a.sampleRate = int(u32(pos + 12));
            bits = int(u16(pos + 22));
            if (fmt == 0xFFFE && len >= 40) fmt = int(u16(pos + 8 + 24));
        } else if (!std::memcmp(b.data() + pos, "data", 4)) {
            dataOff = pos + 8;
            dataLen = std::min<size_t>(len, b.size() - dataOff);
            break;
        }
        pos += 8 + len + (len & 1);
    }
    if (!dataOff || a.channels < 1) { err = "no audio data"; return false; }
    const int bps = bits / 8;
    if (!((fmt == 1 && (bits == 16 || bits == 24 || bits == 32)) || (fmt == 3 && bits == 32))) {
        err = "unsupported WAV format (PCM 16/24/32 or float32 only)"; return false;
    }
    const size_t frames = dataLen / size_t(bps * a.channels);
    a.data.assign(size_t(a.channels), std::vector<float>(frames));
    const uint8_t* p = b.data() + dataOff;
    for (size_t i = 0; i < frames; ++i)
        for (int c = 0; c < a.channels; ++c, p += bps) {
            float v;
            if (fmt == 3) { std::memcpy(&v, p, 4); }
            else if (bits == 16) v = float(int16_t(p[0] | (p[1] << 8))) / 32768.f;
            else if (bits == 24) v = float(int32_t((uint32_t(p[0]) << 8) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 24)) >> 8) / 8388608.f;
            else v = float(double(int32_t(uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24))) / 2147483648.0);
            a.data[size_t(c)][i] = v;
        }
    return true;
}

// writes 24-bit PCM
inline bool write(const std::string& path, const Audio& a) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const uint32_t frames = uint32_t(a.frames()), ch = uint32_t(a.channels);
    const uint32_t dataLen = frames * ch * 3;
    auto w32 = [&](uint32_t v) { uint8_t x[4] = {uint8_t(v), uint8_t(v >> 8), uint8_t(v >> 16), uint8_t(v >> 24)}; std::fwrite(x, 1, 4, f); };
    auto w16 = [&](uint32_t v) { uint8_t x[2] = {uint8_t(v), uint8_t(v >> 8)}; std::fwrite(x, 1, 2, f); };
    std::fwrite("RIFF", 1, 4, f); w32(36 + dataLen); std::fwrite("WAVE", 1, 4, f);
    std::fwrite("fmt ", 1, 4, f); w32(16); w16(1); w16(ch); w32(uint32_t(a.sampleRate));
    w32(uint32_t(a.sampleRate) * ch * 3); w16(ch * 3); w16(24);
    std::fwrite("data", 1, 4, f); w32(dataLen);
    std::vector<uint8_t> buf(static_cast<size_t>(dataLen));
    size_t k = 0;
    for (uint32_t i = 0; i < frames; ++i)
        for (uint32_t c = 0; c < ch; ++c) {
            float v = a.data[c][i];
            v = v > 1.f ? 1.f : (v < -1.f ? -1.f : v);
            const int32_t s = int32_t(v * 8388607.f);
            buf[k++] = uint8_t(s); buf[k++] = uint8_t(s >> 8); buf[k++] = uint8_t(s >> 16);
        }
    std::fwrite(buf.data(), 1, buf.size(), f);
    std::fclose(f);
    return true;
}

} // namespace wav
