// LightTune command-line processor
//   lighttune in.wav out.wav [options]      (run with --help for the list)
#include "lighttune/Engine.h"
#include "wav.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>

using namespace lighttune;

static void usage() {
    std::printf(
        "LightTune - lightweight real-time pitch correction\n\n"
        "usage: lighttune <in.wav> <out.wav> [options]\n"
        "       lighttune <in.wav> --detect-key\n\n"
        "Scale\n"
        "  --key <C..B>            key (default C), e.g. F#, Bb\n"
        "  --scale <name>          chromatic major minor harmonic-minor melodic-minor dorian\n"
        "                          phrygian lydian mixolydian locrian major-pent minor-pent\n"
        "                          blues whole-tone phrygian-dominant hungarian-minor\n"
        "  --remove <notes>        notes removed from the scale, e.g. C#,F\n"
        "  --bypass <notes>        notes left uncorrected, e.g. B\n"
        "  --detect-key            analyse the file and print the most likely key\n"
        "Correction\n"
        "  --retune <ms>           retune speed 0..400 (0 = hard tune)     [20]\n"
        "  --humanize <0..100>     slower retune on sustained notes          [0]\n"
        "  --flex <0..100>         Flex-Tune: correct only near the note     [0]\n"
        "  --natvib <dB>           natural vibrato -12..+12                  [0]\n"
        "  --tracking <0..100>     choosy .. relaxed                         [50]\n"
        "  --input <type>          soprano alto-tenor low-male instrument bass [alto-tenor]\n"
        "  --classic               classic mode (old-school retune behaviour)\n"
        "  --low-latency           low latency mode\n"
        "Pitch / formant\n"
        "  --transpose <semis>     -24..24                                   [0]\n"
        "  --detune <cents>        reference pitch offset, -100..100          [0]\n"
        "  --no-formant            disable formant correction\n"
        "  --throat <50..180>      throat length %%                            [100]\n"
        "Create vibrato\n"
        "  --vib-shape <sine|square|saw>  --vib-rate <Hz>  --vib-delay <ms>  --vib-onset <ms>\n"
        "  --vib-pitch <cents>  --vib-amp <%%>  --vib-formant <%%>  --vib-variation <%%>\n"
        "Misc\n"
        "  --mix <0..100>          dry/wet                                   [100]\n"
        "  --pitch-log <file.csv>  write time,input Hz,output Hz,target note\n"
        "  --block <n>             processing block size                    [256]\n");
}

static int parseNote(std::string s) {
    static const std::map<std::string, int> base = {{"C", 0}, {"D", 2}, {"E", 4}, {"F", 5}, {"G", 7}, {"A", 9}, {"B", 11}};
    if (s.empty()) return -1;
    std::string l(1, char(std::toupper(s[0])));
    auto it = base.find(l);
    if (it == base.end()) return -1;
    int n = it->second;
    for (size_t i = 1; i < s.size(); ++i) {
        if (s[i] == '#') ++n;
        else if (s[i] == 'b') --n;
    }
    return ((n % 12) + 12) % 12;
}

static Scale parseScale(const std::string& s) {
    static const std::map<std::string, Scale> m = {
        {"chromatic", Scale::Chromatic}, {"major", Scale::Major}, {"minor", Scale::Minor},
        {"harmonic-minor", Scale::HarmonicMinor}, {"melodic-minor", Scale::MelodicMinor},
        {"dorian", Scale::Dorian}, {"phrygian", Scale::Phrygian}, {"lydian", Scale::Lydian},
        {"mixolydian", Scale::Mixolydian}, {"locrian", Scale::Locrian},
        {"major-pent", Scale::MajorPentatonic}, {"minor-pent", Scale::MinorPentatonic},
        {"blues", Scale::Blues}, {"whole-tone", Scale::WholeTone},
        {"phrygian-dominant", Scale::PhrygianDominant}, {"hungarian-minor", Scale::HungarianMinor}};
    auto it = m.find(s);
    if (it == m.end()) { std::fprintf(stderr, "unknown scale '%s'\n", s.c_str()); std::exit(2); }
    return it->second;
}

static void applyNoteList(const std::string& list, NoteMask& mask, NoteState st) {
    size_t p = 0;
    while (p <= list.size()) {
        size_t q = list.find(',', p);
        if (q == std::string::npos) q = list.size();
        int n = parseNote(list.substr(p, q - p));
        if (n >= 0) mask[size_t(n)] = st;
        p = q + 1;
    }
}

// Krumhansl-Schmuckler key finding on a duration-weighted pitch-class histogram
static void detectKey(const wav::Audio& a) {
    PitchDetector det;
    det.prepare(a.sampleRate, 70, 1100);
    det.setTracking(40);
    double hist[12] = {0};
    for (size_t i = 0; i < a.frames(); ++i) {
        float m = 0;
        for (int c = 0; c < a.channels; ++c) m += a.data[size_t(c)][i];
        if (det.push(m / a.channels)) {
            const auto& e = det.estimate();
            if (!e.voiced) continue;
            double midi = 69 + 12 * std::log2(e.frequency / 440.0);
            int pc = ((int(std::lround(midi)) % 12) + 12) % 12;
            double w = 1.0 - std::min(1.0, std::fabs(midi - std::round(midi)) * 2);
            hist[pc] += 0.5 + w;
        }
    }
    static const double maj[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    static const double mnr[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    auto corr = [&](const double* prof, int key) {
        double mx = 0, my = 0;
        for (int i = 0; i < 12; ++i) { mx += hist[(i + key) % 12]; my += prof[i]; }
        mx /= 12; my /= 12;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < 12; ++i) {
            double dx = hist[(i + key) % 12] - mx, dy = prof[i] - my;
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy;
        }
        return sxx > 0 ? sxy / std::sqrt(sxx * syy) : 0;
    };
    double best = -2; int bk = 0; bool minor = false;
    for (int k = 0; k < 12; ++k) {
        double c1 = corr(maj, k), c2 = corr(mnr, k);
        if (c1 > best) { best = c1; bk = k; minor = false; }
        if (c2 > best) { best = c2; bk = k; minor = true; }
    }
    std::printf("Detected key: %s %s (confidence %.2f)\n", keyName(bk), minor ? "minor" : "major", best);
    std::printf("  use: --key %s --scale %s\n", keyName(bk), minor ? "minor" : "major");
}

int main(int argc, char** argv) {
    if (argc < 2 || !std::strcmp(argv[1], "--help") || !std::strcmp(argv[1], "-h")) { usage(); return argc < 2; }
    std::string inPath = argv[1], outPath;
    int argi = 2;
    if (argc > 2 && argv[2][0] != '-') { outPath = argv[2]; argi = 3; }

    Params p;
    std::string removeList, bypassList, pitchLog;
    bool doDetect = false;
    int block = 256;
    auto need = [&](int& i) -> const char* {
        if (i + 1 >= argc) { std::fprintf(stderr, "missing value for %s\n", argv[i]); std::exit(2); }
        return argv[++i];
    };
    for (int i = argi; i < argc; ++i) {
        std::string o = argv[i];
        if (o == "--key") { int k = parseNote(need(i)); if (k < 0) { std::fprintf(stderr, "bad key\n"); return 2; } p.key = k; }
        else if (o == "--scale") p.scale = parseScale(need(i));
        else if (o == "--remove") removeList = need(i);
        else if (o == "--bypass") bypassList = need(i);
        else if (o == "--detect-key") doDetect = true;
        else if (o == "--retune") p.retuneSpeedMs = float(std::atof(need(i)));
        else if (o == "--humanize") p.humanize = float(std::atof(need(i)));
        else if (o == "--flex") p.flexTune = float(std::atof(need(i)));
        else if (o == "--natvib") p.naturalVibratoDb = float(std::atof(need(i)));
        else if (o == "--tracking") p.tracking = float(std::atof(need(i)));
        else if (o == "--input") {
            std::string t = need(i);
            if (t == "soprano") p.inputType = InputType::Soprano;
            else if (t == "alto-tenor") p.inputType = InputType::AltoTenor;
            else if (t == "low-male") p.inputType = InputType::LowMale;
            else if (t == "instrument") p.inputType = InputType::Instrument;
            else if (t == "bass") p.inputType = InputType::BassInstrument;
            else { std::fprintf(stderr, "unknown input type\n"); return 2; }
        }
        else if (o == "--classic") p.classicMode = true;
        else if (o == "--low-latency") p.lowLatency = true;
        else if (o == "--transpose") p.transpose = float(std::atof(need(i)));
        else if (o == "--detune") p.detuneCents = float(std::atof(need(i)));
        else if (o == "--no-formant") p.formantCorrection = false;
        else if (o == "--throat") p.throatLength = float(std::atof(need(i)));
        else if (o == "--vib-shape") {
            std::string t = need(i);
            p.vibShape = t == "square" ? VibratoShape::Square : t == "saw" ? VibratoShape::Sawtooth : VibratoShape::Sine;
        }
        else if (o == "--vib-rate") p.vibRateHz = float(std::atof(need(i)));
        else if (o == "--vib-delay") p.vibOnsetDelayMs = float(std::atof(need(i)));
        else if (o == "--vib-onset") p.vibOnsetRateMs = float(std::atof(need(i)));
        else if (o == "--vib-pitch") p.vibPitchCents = float(std::atof(need(i)));
        else if (o == "--vib-amp") p.vibAmplitudePct = float(std::atof(need(i)));
        else if (o == "--vib-formant") p.vibFormantPct = float(std::atof(need(i)));
        else if (o == "--vib-variation") p.vibVariation = float(std::atof(need(i)));
        else if (o == "--mix") p.mix = float(std::atof(need(i)));
        else if (o == "--pitch-log") pitchLog = need(i);
        else if (o == "--block") block = std::max(1, std::atoi(need(i)));
        else { std::fprintf(stderr, "unknown option %s (see --help)\n", o.c_str()); return 2; }
    }

    wav::Audio in;
    std::string err;
    if (!wav::read(inPath, in, err)) { std::fprintf(stderr, "error: %s\n", err.c_str()); return 1; }
    if (doDetect) { detectKey(in); if (outPath.empty()) return 0; }
    if (outPath.empty()) { std::fprintf(stderr, "missing output file\n"); return 2; }

    p.applyKeyScale();
    applyNoteList(removeList, p.notes, NoteState::Off);
    applyNoteList(bypassList, p.notes, NoteState::Bypass);

    const int ch = std::min(in.channels, Engine::kMaxChannels);
    Engine eng;
    eng.prepare(in.sampleRate, ch, p);
    const int lat = eng.latencySamples();

    const size_t N = in.frames();
    wav::Audio out;
    out.sampleRate = in.sampleRate;
    out.channels = ch;
    out.data.assign(size_t(ch), std::vector<float>(N, 0.f));

    std::vector<std::vector<float>> ib(static_cast<size_t>(ch), std::vector<float>(static_cast<size_t>(block)));
    std::vector<std::vector<float>> ob = ib;
    const float* ip[2]; float* op[2];
    for (int c = 0; c < ch; ++c) { ip[c] = ib[size_t(c)].data(); op[c] = ob[size_t(c)].data(); }

    FILE* log = pitchLog.empty() ? nullptr : std::fopen(pitchLog.c_str(), "w");
    if (log) std::fprintf(log, "time_s,input_hz,output_hz,target_midi\n");

    const auto t0 = std::chrono::steady_clock::now();
    const size_t total = N + size_t(lat);
    for (size_t pos = 0; pos < total; pos += size_t(block)) {
        const int n = int(std::min<size_t>(size_t(block), total - pos));
        for (int c = 0; c < ch; ++c)
            for (int k = 0; k < n; ++k) ib[size_t(c)][size_t(k)] = (pos + size_t(k) < N) ? in.data[size_t(c)][pos + size_t(k)] : 0.f;
        eng.process(ip, op, n);
        for (int c = 0; c < ch; ++c)
            for (int k = 0; k < n; ++k) {
                const size_t dst = pos + size_t(k);
                if (dst >= size_t(lat) && dst - size_t(lat) < N) out.data[size_t(c)][dst - size_t(lat)] = ob[size_t(c)][size_t(k)];
            }
        if (log) std::fprintf(log, "%.4f,%.2f,%.2f,%.2f\n", double(pos) / in.sampleRate, eng.inputPitchHz(), eng.outputPitchHz(), eng.targetMidi());
    }
    const double sec = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    if (log) std::fclose(log);

    if (!wav::write(outPath, out)) { std::fprintf(stderr, "cannot write %s\n", outPath.c_str()); return 1; }
    const double audioSec = double(N) / in.sampleRate;
    std::printf("Processed %.2f s of audio (%d ch, %d Hz) in %.3f s  ->  %.0fx real time, %.2f%% of one CPU core\n",
                audioSec, ch, in.sampleRate, sec, audioSec / sec, 100.0 * sec / audioSec);
    std::printf("Plugin latency: %d samples (%.1f ms)\n", lat, 1000.0 * lat / in.sampleRate);
    return 0;
}
