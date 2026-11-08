#define _USE_MATH_DEFINES
// Minimal CLAP host: loads LightTune.clap like a DAW would, checks the
// extensions, processes audio with automation events and round-trips the state.
#include <clap/clap.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <vector>

static int failures = 0;
#define CHECK(c, ...) do { std::printf((c) ? "  ok:   " : "  FAIL: "); std::printf(__VA_ARGS__); std::printf("\n"); if (!(c)) ++failures; } while (0)

static bool restartRequested = false;
static const void* hostExt(const clap_host_t*, const char*) { return nullptr; }
static void hostNoop(const clap_host_t*) {}
static void hostRestart(const clap_host_t*) { restartRequested = true; }
static const clap_host_t kHost = {CLAP_VERSION_INIT, nullptr, "test-host", "lighttune", "", "1.0",
                                  hostExt, hostRestart, hostNoop, hostNoop};

struct Events {
    std::vector<clap_event_param_value_t> ev;
    static uint32_t size(const clap_input_events_t* l) { return uint32_t(static_cast<Events*>(l->ctx)->ev.size()); }
    static const clap_event_header_t* get(const clap_input_events_t* l, uint32_t i) { return &static_cast<Events*>(l->ctx)->ev[i].header; }
};
static bool tryPush(const clap_output_events_t*, const clap_event_header_t*) { return true; }

struct Buf { std::vector<uint8_t> d; size_t rd = 0; };
static int64_t sWrite(const clap_ostream_t* s, const void* b, uint64_t n) { auto* B = static_cast<Buf*>(s->ctx); B->d.insert(B->d.end(), (const uint8_t*)b, (const uint8_t*)b + n); return int64_t(n); }
static int64_t sRead(const clap_istream_t* s, void* b, uint64_t n) { auto* B = static_cast<Buf*>(s->ctx); size_t k = std::min<size_t>(n, B->d.size() - B->rd); std::memcpy(b, B->d.data() + B->rd, k); B->rd += k; return int64_t(k); }

static clap_event_param_value_t pv(uint32_t t, clap_id id, double v) {
    clap_event_param_value_t e{};
    e.header = {sizeof(e), t, CLAP_CORE_EVENT_SPACE_ID, CLAP_EVENT_PARAM_VALUE, 0};
    e.param_id = id; e.note_id = -1; e.port_index = -1; e.channel = -1; e.key = -1; e.value = v;
    return e;
}

int main(int argc, char** argv) {
    if (argc < 2) { std::printf("usage: %s LightTune.clap\n", argv[0]); return 2; }
    void* lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!lib) { std::printf("dlopen failed: %s\n", dlerror()); return 1; }
    auto* entry = static_cast<const clap_plugin_entry_t*>(dlsym(lib, "clap_entry"));
    CHECK(entry != nullptr, "clap_entry exported");
    if (!entry) return 1;
    CHECK(entry->init(argv[1]), "entry init");
    auto* fac = static_cast<const clap_plugin_factory_t*>(entry->get_factory(CLAP_PLUGIN_FACTORY_ID));
    CHECK(fac && fac->get_plugin_count(fac) == 1, "factory with 1 plugin");
    const auto* desc = fac->get_plugin_descriptor(fac, 0);
    std::printf("  plugin: %s (%s) v%s\n", desc->name, desc->id, desc->version);
    const clap_plugin_t* p = fac->create_plugin(fac, &kHost, desc->id);
    CHECK(p && p->init(p), "create + init");

    auto* params = static_cast<const clap_plugin_params_t*>(p->get_extension(p, CLAP_EXT_PARAMS));
    auto* lat = static_cast<const clap_plugin_latency_t*>(p->get_extension(p, CLAP_EXT_LATENCY));
    auto* state = static_cast<const clap_plugin_state_t*>(p->get_extension(p, CLAP_EXT_STATE));
    auto* ports = static_cast<const clap_plugin_audio_ports_t*>(p->get_extension(p, CLAP_EXT_AUDIO_PORTS));
    CHECK(params && lat && state && ports, "params / latency / state / audio-ports extensions");
    CHECK(params->count(p) >= 30, "%u parameters", params->count(p));
    char txt[64];
    params->value_to_text(p, 1, 1.0, txt, sizeof txt);
    CHECK(!std::strcmp(txt, "Major"), "scale value 1 displays '%s'", txt);

    const double fs = 48000;
    const uint32_t B = 480;
    CHECK(p->activate(p, fs, 1, B), "activate @ 48 kHz");
    p->start_processing(p);
    const uint32_t L = lat->get(p);
    std::printf("  latency = %u samples (%.1f ms)\n", L, 1000.0 * L / fs);

    // input: sawtooth at A3 + 45 cents, stereo
    const double f0 = 220.0 * std::pow(2.0, 45 / 1200.0);
    const int blocks = 300;
    std::vector<float> inL(B), inR(B), outL(B * blocks), outR(B * blocks);
    double ph = 0;
    Events evs;
    clap_input_events_t in{&evs, Events::size, Events::get};
    clap_output_events_t out{nullptr, tryPush};
    for (int b = 0; b < blocks; ++b) {
        for (uint32_t i = 0; i < B; ++i) {
            ph += f0 / fs; ph -= std::floor(ph);
            double s = 0;
            for (int h = 1; h < 30; ++h) s += std::sin(2 * M_PI * h * ph) / h;
            inL[i] = inR[i] = float(0.3 * s);
        }
        evs.ev.clear();
        if (b == 0) { evs.ev.push_back(pv(0, 2, 0.0)); }          // retune speed 0 ms
        if (b == 0) { evs.ev.push_back(pv(10, 1, 1.0)); }         // C major (A is in scale)
        float* ins[2] = {inL.data(), inR.data()};
        float* outs[2] = {outL.data() + b * B, outR.data() + b * B};
        clap_audio_buffer_t ib{ins, nullptr, 2, 0, 0}, ob{outs, nullptr, 2, 0, 0};
        clap_process_t pr{};
        pr.steady_time = int64_t(b) * B; pr.frames_count = B; pr.audio_inputs = &ib; pr.audio_outputs = &ob;
        pr.audio_inputs_count = 1; pr.audio_outputs_count = 1; pr.in_events = &in; pr.out_events = &out;
        if (p->process(p, &pr) != CLAP_PROCESS_CONTINUE) { CHECK(false, "process block %d", b); }
    }

    // measure the output pitch with a brute-force difference function (lag search 160..260 Hz)
    double sumLag = 0; int nWin = 0;
    for (size_t c = size_t(fs * 1.0); c + 2400 < outL.size(); c += 4800) {
        int best = 0; double bv = 1e30, d[400];
        for (int lag = int(fs / 260); lag <= int(fs / 160); ++lag) {
            double s = 0;
            for (int j = 0; j < 1200; ++j) { double e = outL[c + j] - outL[c + j + lag]; s += e * e; }
            d[lag - int(fs / 260)] = s;
            if (s < bv) { bv = s; best = lag; }
        }
        const int k = best - int(fs / 260);
        double frac = 0.5 * (d[k - 1] - d[k + 1]) / (d[k - 1] - 2 * d[k] + d[k + 1]);
        sumLag += best + frac; ++nWin;
    }
    const double fout = fs / (sumLag / nWin);
    const double cents = 1200 * std::log2(fout / 220.0);
    CHECK(std::fabs(cents) < 2.0, "output %.2f Hz (%+.2f cents from A3; input was +45 cents)", fout, cents);
    bool same = true;
    for (size_t i = 0; i < outL.size(); ++i) same = same && outL[i] == outR[i];
    CHECK(same, "stereo channels processed identically for identical input");

    // automation of input type -> plugin asks the host for a restart (latency change)
    {
        evs.ev = {pv(0, 7, 2.0)};
        float* ins[2] = {inL.data(), inR.data()};
        float* outs[2] = {inL.data(), inR.data()};
        clap_audio_buffer_t ib{ins, nullptr, 2, 0, 0}, ob{outs, nullptr, 2, 0, 0};
        clap_process_t pr{}; pr.frames_count = B; pr.audio_inputs = &ib; pr.audio_outputs = &ob;
        pr.audio_inputs_count = 1; pr.audio_outputs_count = 1; pr.in_events = &in; pr.out_events = &out;
        p->process(p, &pr);
        CHECK(restartRequested, "input type change requests a host restart");
    }
    p->stop_processing(p);
    p->deactivate(p);
    CHECK(p->activate(p, fs, 1, B), "re-activate");
    CHECK(lat->get(p) != L, "latency updated after restart (%u -> %u)", L, lat->get(p));

    // state round trip
    Buf buf;
    clap_ostream_t os{&buf, sWrite};
    CHECK(state->save(p, &os), "state save (%zu bytes)", buf.d.size());
    params->flush(p, &in, &out);
    clap_istream_t is{&buf, sRead};
    const clap_plugin_t* p2 = fac->create_plugin(fac, &kHost, desc->id);
    p2->init(p2);
    auto* params2 = static_cast<const clap_plugin_params_t*>(p2->get_extension(p2, CLAP_EXT_PARAMS));
    auto* state2 = static_cast<const clap_plugin_state_t*>(p2->get_extension(p2, CLAP_EXT_STATE));
    CHECK(state2->load(p2, &is), "state load");
    double v1, v2, v3;
    params2->get_value(p2, 2, &v1); params2->get_value(p2, 1, &v2); params2->get_value(p2, 7, &v3);
    CHECK(v1 == 0.0 && v2 == 1.0 && v3 == 2.0, "restored retune=%.0f scale=%.0f inputType=%.0f", v1, v2, v3);

    p->deactivate(p);
    p->destroy(p);
    p2->destroy(p2);
    entry->deinit();
    std::printf(failures ? "\n%d FAILURE(S)\n" : "\nCLAP HOST TEST PASSED\n", failures);
    return failures ? 1 : 0;
}
