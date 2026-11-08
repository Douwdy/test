#include "PluginProcessor.h"
#include "PluginEditor.h"

using namespace lighttune;

juce::AudioProcessorValueTreeState::ParameterLayout LightTuneProcessor::createLayout() {
    juce::AudioProcessorValueTreeState::ParameterLayout layout;
    for (int i = 0; i < kNumParams; ++i) {
        const auto& d = paramDefs()[size_t(i)];
        const int id = i;
        auto toText = [id](float v, int) {
            char buf[64];
            formatParam(id, v, buf, sizeof buf);
            return juce::String(buf);
        };
        juce::NormalisableRange<float> range(d.min, d.max, d.stepped ? 1.f : 0.f);
        if (i == kRetune) range.setSkewForCentre(40.f);
        if (i == kVibDelay || i == kVibOnset) range.setSkewForCentre(400.f);
        layout.add(std::make_unique<juce::AudioParameterFloat>(
            juce::ParameterID{d.id, 1}, d.name, range, d.def,
            juce::AudioParameterFloatAttributes().withStringFromValueFunction(toText).withLabel(d.unit)));
    }
    return layout;
}

LightTuneProcessor::LightTuneProcessor()
    : AudioProcessor(BusesProperties()
                         .withInput("Input", juce::AudioChannelSet::stereo(), true)
                         .withOutput("Output", juce::AudioChannelSet::stereo(), true)),
      apvts(*this, nullptr, "LightTune", createLayout()) {
    for (int i = 0; i < kNumParams; ++i) raw_[size_t(i)] = apvts.getRawParameterValue(paramDefs()[size_t(i)].id);
}

bool LightTuneProcessor::isBusesLayoutSupported(const BusesLayout& layouts) const {
    const auto in = layouts.getMainInputChannelSet(), out = layouts.getMainOutputChannelSet();
    if (in != out) return false;
    return out == juce::AudioChannelSet::mono() || out == juce::AudioChannelSet::stereo();
}

void LightTuneProcessor::prepareToPlay(double sampleRate, int samplesPerBlock) {
    sampleRate_ = sampleRate;
    blockSize_ = samplesPerBlock;
    for (int i = 0; i < kNumParams; ++i) values_[size_t(i)] = raw_[size_t(i)]->load();
    const int ch = std::max(1, std::min(2, getTotalNumInputChannels()));
    engine_.prepare(sampleRate, ch, paramsFromValues(values_.data()));
    setLatencySamples(engine_.latencySamples());
    reprepare_.store(false);
}

void LightTuneProcessor::handleAsyncUpdate() {
    // Changing the input type or the latency mode changes the plugin latency:
    // re-prepare outside the audio thread, then tell the host.
    suspendProcessing(true);
    prepareToPlay(sampleRate_, blockSize_);
    suspendProcessing(false);
}

void LightTuneProcessor::processBlock(juce::AudioBuffer<float>& buffer, juce::MidiBuffer& midi) {
    juce::ScopedNoDenormals noDenormals;
    for (const auto m : midi) {
        const auto msg = m.getMessage();
        if (msg.isNoteOn()) held_.set(size_t(msg.getNoteNumber()));
        else if (msg.isNoteOff()) held_.reset(size_t(msg.getNoteNumber()));
        else if (msg.isAllNotesOff() || msg.isAllSoundOff()) held_.reset();
    }
    for (int i = 0; i < kNumParams; ++i) values_[size_t(i)] = raw_[size_t(i)]->load();
    if (engine_.setParams(paramsFromValues(values_.data())) && !reprepare_.exchange(true)) triggerAsyncUpdate();
    engine_.setMidiNotes(held_);

    const int ch = std::min(2, buffer.getNumChannels());
    if (ch == 0) return;
    const float* in[2] = {buffer.getReadPointer(0), buffer.getReadPointer(ch > 1 ? 1 : 0)};
    float* out[2] = {buffer.getWritePointer(0), buffer.getWritePointer(ch > 1 ? 1 : 0)};
    engine_.process(in, out, buffer.getNumSamples());
}

void LightTuneProcessor::getStateInformation(juce::MemoryBlock& destData) {
    if (auto xml = apvts.copyState().createXml()) copyXmlToBinary(*xml, destData);
}

void LightTuneProcessor::setStateInformation(const void* data, int sizeInBytes) {
    if (auto xml = getXmlFromBinary(data, sizeInBytes))
        if (xml->hasTagName(apvts.state.getType())) apvts.replaceState(juce::ValueTree::fromXml(*xml));
}

juce::AudioProcessorEditor* LightTuneProcessor::createEditor() { return new LightTuneEditor(*this); }

juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter() { return new LightTuneProcessor(); }
