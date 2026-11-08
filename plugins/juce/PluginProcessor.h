#pragma once

#include <juce_audio_processors/juce_audio_processors.h>

#include "lighttune/Engine.h"
#include "lighttune/ParamDefs.h"

#include <array>
#include <atomic>
#include <bitset>

class LightTuneProcessor : public juce::AudioProcessor, private juce::AsyncUpdater {
public:
    LightTuneProcessor();
    ~LightTuneProcessor() override = default;

    void prepareToPlay(double sampleRate, int samplesPerBlock) override;
    void releaseResources() override {}
    bool isBusesLayoutSupported(const BusesLayout& layouts) const override;
    void processBlock(juce::AudioBuffer<float>&, juce::MidiBuffer&) override;

    juce::AudioProcessorEditor* createEditor() override;
    bool hasEditor() const override { return true; }

    const juce::String getName() const override { return "LightTune"; }
    bool acceptsMidi() const override { return true; }
    bool producesMidi() const override { return false; }
    bool isMidiEffect() const override { return false; }
    double getTailLengthSeconds() const override { return 0.0; }

    int getNumPrograms() override { return 1; }
    int getCurrentProgram() override { return 0; }
    void setCurrentProgram(int) override {}
    const juce::String getProgramName(int) override { return {}; }
    void changeProgramName(int, const juce::String&) override {}

    void getStateInformation(juce::MemoryBlock& destData) override;
    void setStateInformation(const void* data, int sizeInBytes) override;

    juce::AudioProcessorValueTreeState apvts;
    lighttune::Engine& engine() { return engine_; }

private:
    static juce::AudioProcessorValueTreeState::ParameterLayout createLayout();
    void handleAsyncUpdate() override; // re-prepare when input type / latency mode changes

    lighttune::Engine engine_;
    std::array<std::atomic<float>*, lighttune::kNumParams> raw_{};
    std::array<float, lighttune::kNumParams> values_{};
    std::bitset<128> held_;
    double sampleRate_ = 44100;
    int blockSize_ = 512;
    std::atomic<bool> reprepare_{false};

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(LightTuneProcessor)
};
