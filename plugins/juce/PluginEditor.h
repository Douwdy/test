#pragma once

#include "PluginProcessor.h"

#include <juce_gui_basics/juce_gui_basics.h>

#include <memory>
#include <vector>

class LightTuneEditor : public juce::AudioProcessorEditor, private juce::Timer {
public:
    explicit LightTuneEditor(LightTuneProcessor&);
    ~LightTuneEditor() override;

    void paint(juce::Graphics&) override;
    void resized() override;

private:
    using SliderAttachment = juce::AudioProcessorValueTreeState::SliderAttachment;
    using ComboAttachment = juce::AudioProcessorValueTreeState::ComboBoxAttachment;
    using ButtonAttachment = juce::AudioProcessorValueTreeState::ButtonAttachment;

    struct Knob {
        juce::Slider slider;
        juce::Label label;
        std::unique_ptr<SliderAttachment> att;
    };
    struct Combo {
        juce::ComboBox box;
        juce::Label label;
        std::unique_ptr<ComboAttachment> att;
    };
    struct Toggle {
        juce::ToggleButton button;
        std::unique_ptr<ButtonAttachment> att;
    };

    Knob& addKnob(int paramId, bool big = false);
    Combo& addCombo(int paramId, const juce::StringArray& items);
    Toggle& addToggle(int paramId, const juce::String& text);

    void timerCallback() override;
    void paintGraph(juce::Graphics&, juce::Rectangle<int>);
    void paintMeter(juce::Graphics&, juce::Rectangle<int>);

    LightTuneProcessor& proc_;
    juce::LookAndFeel_V4 lnf_;

    std::vector<std::unique_ptr<Knob>> knobs_;
    std::vector<std::unique_ptr<Combo>> combos_;
    std::vector<std::unique_ptr<Toggle>> toggles_;
    std::array<std::unique_ptr<Combo>, 12> notes_;

    Knob *retune_{}, *humanize_{}, *flex_{}, *natvib_{}, *tracking_{}, *transpose_{}, *detune_{}, *throat_{}, *mix_{};
    Knob *vibRate_{}, *vibDelay_{}, *vibOnset_{}, *vibVar_{}, *vibPitch_{}, *vibAmp_{}, *vibFormant_{};
    Combo *key_{}, *scale_{}, *input_{}, *shape_{};
    Toggle *formant_{}, *classic_{}, *lowLat_{}, *midi_{};

    static constexpr int kHistory = 400;
    std::array<float, kHistory> inHist_{}, outHist_{};
    int histPos_ = 0;
    float cents_ = 0;
    juce::Rectangle<int> graphArea_, meterArea_;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(LightTuneEditor)
};
