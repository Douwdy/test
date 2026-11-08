// LightTune CLAP plugin (Bitwig, Reaper, FL Studio, Studio One, MultitrackStudio, ...)
// Header-only dependency: the CLAP SDK headers (MIT). No GUI: hosts show a generic editor.
#include <clap/clap.h>

#include "lighttune/Engine.h"
#include "lighttune/ParamDefs.h"

#include <atomic>
#include <bitset>
#include <cstring>
#include <vector>

using namespace lighttune;

namespace {

struct LightTunePlugin {
    clap_plugin_t plugin{};
    const clap_host_t* host = nullptr;
    const clap_host_latency_t* hostLatency = nullptr;
    const clap_host_params_t* hostParams = nullptr;
    const clap_host_state_t* hostState = nullptr;

    Engine engine;
    std::atomic<float> shared[kNumParams];   // main-thread view
    float audio[kNumParams];                 // audio-thread copy
    std::atomic<bool> mainChanged{false};
    std::atomic<bool> restartPending{false};
    std::bitset<128> held;
    double sampleRate = 44100;
    bool active = false;
    std::vector<float> scratchL, scratchR;

    LightTunePlugin() {
        for (int i = 0; i < kNumParams; ++i) {
            shared[i].store(paramDefs()[size_t(i)].def);
            audio[i] = paramDefs()[size_t(i)].def;
        }
    }
};

LightTunePlugin* self(const clap_plugin_t* p) { return static_cast<LightTunePlugin*>(p->plugin_data); }

const char* kFeatures[] = {CLAP_PLUGIN_FEATURE_AUDIO_EFFECT, CLAP_PLUGIN_FEATURE_PITCH_CORRECTION,
                           CLAP_PLUGIN_FEATURE_MONO, CLAP_PLUGIN_FEATURE_STEREO, nullptr};

const clap_plugin_descriptor_t kDesc = {
    CLAP_VERSION_INIT,
    "com.lighttune.pitchcorrector",
    "LightTune",
    "LightTune",
    "https://github.com/douwdy/test",
    "",
    "",
    "1.0.0",
    "Lightweight real-time vocal pitch correction",
    kFeatures};

// ---------------------------------------------------------------- audio ports
uint32_t portsCount(const clap_plugin_t*, bool) { return 1; }
bool portsGet(const clap_plugin_t*, uint32_t index, bool isInput, clap_audio_port_info_t* info) {
    if (index != 0) return false;
    info->id = isInput ? 0 : 1;
    std::snprintf(info->name, sizeof(info->name), "%s", isInput ? "Input" : "Output");
    info->flags = CLAP_AUDIO_PORT_IS_MAIN;
    info->channel_count = 2;
    info->port_type = CLAP_PORT_STEREO;
    info->in_place_pair = isInput ? 1 : 0;
    return true;
}
const clap_plugin_audio_ports_t kPorts = {portsCount, portsGet};

// ----------------------------------------------------------------- note ports
uint32_t notePortsCount(const clap_plugin_t*, bool isInput) { return isInput ? 1 : 0; }
bool notePortsGet(const clap_plugin_t*, uint32_t index, bool isInput, clap_note_port_info_t* info) {
    if (!isInput || index != 0) return false;
    info->id = 0;
    info->supported_dialects = CLAP_NOTE_DIALECT_CLAP | CLAP_NOTE_DIALECT_MIDI;
    info->preferred_dialect = CLAP_NOTE_DIALECT_MIDI;
    std::snprintf(info->name, sizeof(info->name), "Target Notes");
    return true;
}
const clap_plugin_note_ports_t kNotePorts = {notePortsCount, notePortsGet};

// --------------------------------------------------------------------- params
uint32_t paramsCount(const clap_plugin_t*) { return kNumParams; }
bool paramsInfo(const clap_plugin_t*, uint32_t index, clap_param_info_t* info) {
    if (index >= kNumParams) return false;
    const auto& d = paramDefs()[index];
    std::memset(info, 0, sizeof(*info));
    info->id = index;
    info->flags = CLAP_PARAM_IS_AUTOMATABLE;
    if (d.stepped) info->flags |= CLAP_PARAM_IS_STEPPED;
    if (d.stepped && index != kTranspose && index != kKey && d.max - d.min < 20) info->flags |= CLAP_PARAM_IS_ENUM;
    std::snprintf(info->name, sizeof(info->name), "%s", d.name);
    std::snprintf(info->module, sizeof(info->module), "%s", d.module);
    info->min_value = d.min;
    info->max_value = d.max;
    info->default_value = d.def;
    return true;
}
bool paramsValue(const clap_plugin_t* p, clap_id id, double* value) {
    if (id >= kNumParams) return false;
    *value = self(p)->shared[id].load();
    return true;
}
bool paramsToText(const clap_plugin_t*, clap_id id, double value, char* out, uint32_t size) {
    if (id >= kNumParams) return false;
    formatParam(int(id), value, out, int(size));
    return true;
}
bool paramsFromText(const clap_plugin_t*, clap_id id, const char* text, double* value) {
    if (id >= kNumParams) return false;
    char* end = nullptr;
    const double v = std::strtod(text, &end);
    if (end == text) return false;
    *value = v;
    return true;
}

void handleEvent(LightTunePlugin* s, const clap_event_header_t* h) {
    if (h->space_id != CLAP_CORE_EVENT_SPACE_ID) return;
    switch (h->type) {
        case CLAP_EVENT_PARAM_VALUE: {
            auto* e = reinterpret_cast<const clap_event_param_value_t*>(h);
            if (e->param_id < kNumParams) {
                const auto& d = paramDefs()[e->param_id];
                const float v = std::clamp(float(e->value), d.min, d.max);
                s->audio[e->param_id] = v;
                s->shared[e->param_id].store(v);
            }
            break;
        }
        case CLAP_EVENT_NOTE_ON: {
            auto* e = reinterpret_cast<const clap_event_note_t*>(h);
            if (e->key >= 0 && e->key < 128) s->held.set(size_t(e->key));
            break;
        }
        case CLAP_EVENT_NOTE_OFF: case CLAP_EVENT_NOTE_CHOKE: {
            auto* e = reinterpret_cast<const clap_event_note_t*>(h);
            if (e->key >= 0 && e->key < 128) s->held.reset(size_t(e->key));
            else if (e->key == -1) s->held.reset();
            break;
        }
        case CLAP_EVENT_MIDI: {
            auto* e = reinterpret_cast<const clap_event_midi_t*>(h);
            const uint8_t st = e->data[0] & 0xF0, k = e->data[1] & 0x7F, vel = e->data[2];
            if (st == 0x90 && vel > 0) s->held.set(k);
            else if (st == 0x80 || (st == 0x90 && vel == 0)) s->held.reset(k);
            else if (st == 0xB0 && (k == 123 || k == 120)) s->held.reset();
            break;
        }
        default: break;
    }
}

void applyParams(LightTunePlugin* s) {
    const bool needRestart = s->engine.setParams(paramsFromValues(s->audio));
    s->engine.setMidiNotes(s->held);
    if (needRestart && !s->restartPending.exchange(true)) s->host->request_restart(s->host);
}

void paramsFlush(const clap_plugin_t* p, const clap_input_events_t* in, const clap_output_events_t*) {
    auto* s = self(p);
    const uint32_t n = in->size(in);
    for (uint32_t i = 0; i < n; ++i) handleEvent(s, in->get(in, i));
    if (s->active) applyParams(s);
}
const clap_plugin_params_t kParams = {paramsCount, paramsInfo, paramsValue, paramsToText, paramsFromText, paramsFlush};

// -------------------------------------------------------------------- latency
uint32_t latencyGet(const clap_plugin_t* p) { return uint32_t(self(p)->engine.latencySamples()); }
const clap_plugin_latency_t kLatency = {latencyGet};

// ---------------------------------------------------------------------- state
bool stateSave(const clap_plugin_t* p, const clap_ostream_t* os) {
    auto* s = self(p);
    const char magic[4] = {'L', 'T', 'N', '1'};
    if (os->write(os, magic, 4) != 4) return false;
    const uint32_t n = kNumParams;
    if (os->write(os, &n, 4) != 4) return false;
    for (int i = 0; i < kNumParams; ++i) {
        const float v = s->shared[i].load();
        if (os->write(os, &v, 4) != 4) return false;
    }
    return true;
}
bool readAll(const clap_istream_t* is, void* dst, int64_t len) {
    auto* b = static_cast<uint8_t*>(dst);
    while (len > 0) {
        const int64_t r = is->read(is, b, uint64_t(len));
        if (r <= 0) return false;
        b += r; len -= r;
    }
    return true;
}
bool stateLoad(const clap_plugin_t* p, const clap_istream_t* is) {
    auto* s = self(p);
    char magic[4];
    uint32_t n = 0;
    if (!readAll(is, magic, 4) || std::memcmp(magic, "LTN1", 4) || !readAll(is, &n, 4)) return false;
    for (uint32_t i = 0; i < n; ++i) {
        float v;
        if (!readAll(is, &v, 4)) return false;
        if (i < uint32_t(kNumParams)) {
            const auto& d = paramDefs()[i];
            s->shared[i].store(std::clamp(v, d.min, d.max));
        }
    }
    s->mainChanged.store(true);
    if (s->hostParams) s->hostParams->rescan(s->host, CLAP_PARAM_RESCAN_VALUES);
    return true;
}
const clap_plugin_state_t kState = {stateSave, stateLoad};

// --------------------------------------------------------------------- plugin
bool pInit(const clap_plugin_t* p) {
    auto* s = self(p);
    s->hostLatency = static_cast<const clap_host_latency_t*>(s->host->get_extension(s->host, CLAP_EXT_LATENCY));
    s->hostParams = static_cast<const clap_host_params_t*>(s->host->get_extension(s->host, CLAP_EXT_PARAMS));
    s->hostState = static_cast<const clap_host_state_t*>(s->host->get_extension(s->host, CLAP_EXT_STATE));
    return true;
}
void pDestroy(const clap_plugin_t* p) { delete self(p); }

bool pActivate(const clap_plugin_t* p, double sr, uint32_t, uint32_t maxFrames) {
    auto* s = self(p);
    for (int i = 0; i < kNumParams; ++i) s->audio[i] = s->shared[i].load();
    s->mainChanged.store(false);
    s->sampleRate = sr;
    const int oldLatency = s->engine.latencySamples();
    s->engine.prepare(sr, 2, paramsFromValues(s->audio));
    s->scratchL.assign(maxFrames, 0.f);
    s->scratchR.assign(maxFrames, 0.f);
    s->restartPending.store(false);
    s->active = true;
    if (s->hostLatency && oldLatency != s->engine.latencySamples()) s->hostLatency->changed(s->host);
    return true;
}
void pDeactivate(const clap_plugin_t* p) { self(p)->active = false; }
bool pStartProcessing(const clap_plugin_t*) { return true; }
void pStopProcessing(const clap_plugin_t*) {}
void pReset(const clap_plugin_t* p) { self(p)->engine.reset(); }

clap_process_status pProcess(const clap_plugin_t* p, const clap_process_t* proc) {
    auto* s = self(p);
    if (s->mainChanged.exchange(false))
        for (int i = 0; i < kNumParams; ++i) s->audio[i] = s->shared[i].load();

    const uint32_t frames = proc->frames_count;
    if (proc->audio_inputs_count < 1 || proc->audio_outputs_count < 1 || frames == 0) return CLAP_PROCESS_CONTINUE;
    const auto& ib = proc->audio_inputs[0];
    const auto& ob = proc->audio_outputs[0];
    if (!ib.data32 || !ob.data32 || ob.channel_count < 1) return CLAP_PROCESS_CONTINUE;

    const float* inL = ib.data32[0];
    const float* inR = ib.channel_count > 1 ? ib.data32[1] : ib.data32[0];
    float* outL = ob.data32[0];
    float* outR = ob.channel_count > 1 ? ob.data32[1] : s->scratchR.data();
    if (frames > s->scratchL.size()) return CLAP_PROCESS_ERROR;

    // sample-accurate parameter / note events: split the block at each event
    const uint32_t nev = proc->in_events->size(proc->in_events);
    uint32_t ev = 0, pos = 0;
    applyParams(s);
    while (pos < frames) {
        bool changed = false;
        while (ev < nev) {
            const clap_event_header_t* h = proc->in_events->get(proc->in_events, ev);
            if (h->time > pos) break;
            handleEvent(s, h);
            ++ev;
            changed = true;
        }
        if (changed) applyParams(s);
        uint32_t end = frames;
        if (ev < nev) end = std::min(frames, proc->in_events->get(proc->in_events, ev)->time);
        const float* ins[2] = {inL + pos, inR + pos};
        float* outs[2] = {outL + pos, outR + pos};
        s->engine.process(ins, outs, int(end - pos));
        pos = end;
    }
    return CLAP_PROCESS_CONTINUE;
}

const void* pGetExtension(const clap_plugin_t*, const char* id) {
    if (!std::strcmp(id, CLAP_EXT_AUDIO_PORTS)) return &kPorts;
    if (!std::strcmp(id, CLAP_EXT_NOTE_PORTS)) return &kNotePorts;
    if (!std::strcmp(id, CLAP_EXT_PARAMS)) return &kParams;
    if (!std::strcmp(id, CLAP_EXT_LATENCY)) return &kLatency;
    if (!std::strcmp(id, CLAP_EXT_STATE)) return &kState;
    return nullptr;
}
void pOnMainThread(const clap_plugin_t*) {}

const clap_plugin_t* createPlugin(const clap_host_t* host) {
    auto* s = new LightTunePlugin();
    s->host = host;
    s->plugin.desc = &kDesc;
    s->plugin.plugin_data = s;
    s->plugin.init = pInit;
    s->plugin.destroy = pDestroy;
    s->plugin.activate = pActivate;
    s->plugin.deactivate = pDeactivate;
    s->plugin.start_processing = pStartProcessing;
    s->plugin.stop_processing = pStopProcessing;
    s->plugin.reset = pReset;
    s->plugin.process = pProcess;
    s->plugin.get_extension = pGetExtension;
    s->plugin.on_main_thread = pOnMainThread;
    return &s->plugin;
}

// -------------------------------------------------------------------- factory
uint32_t fCount(const clap_plugin_factory_t*) { return 1; }
const clap_plugin_descriptor_t* fDesc(const clap_plugin_factory_t*, uint32_t i) { return i == 0 ? &kDesc : nullptr; }
const clap_plugin_t* fCreate(const clap_plugin_factory_t*, const clap_host_t* host, const char* id) {
    if (!clap_version_is_compatible(host->clap_version) || std::strcmp(id, kDesc.id)) return nullptr;
    return createPlugin(host);
}
const clap_plugin_factory_t kFactory = {fCount, fDesc, fCreate};

bool eInit(const char*) { return true; }
void eDeinit() {}
const void* eGetFactory(const char* id) { return !std::strcmp(id, CLAP_PLUGIN_FACTORY_ID) ? &kFactory : nullptr; }

} // namespace

extern "C" CLAP_EXPORT const clap_plugin_entry_t clap_entry = {CLAP_VERSION_INIT, eInit, eDeinit, eGetFactory};
