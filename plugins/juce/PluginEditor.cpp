#include "PluginEditor.h"

using namespace lighttune;

namespace {
const juce::Colour kBg(0xff16181d), kPanel(0xff21252c), kAccent(0xff35c7a5), kAccent2(0xffff8a3d), kText(0xffd8dde6);
}

LightTuneEditor::LightTuneEditor(LightTuneProcessor& p) : AudioProcessorEditor(&p), proc_(p) {
    lnf_.setColour(juce::Slider::rotarySliderFillColourId, kAccent);
    lnf_.setColour(juce::Slider::thumbColourId, kAccent);
    lnf_.setColour(juce::Slider::rotarySliderOutlineColourId, juce::Colour(0xff3a3f48));
    lnf_.setColour(juce::Label::textColourId, kText);
    lnf_.setColour(juce::ComboBox::backgroundColourId, kPanel);
    lnf_.setColour(juce::ComboBox::outlineColourId, juce::Colour(0xff3a3f48));
    lnf_.setColour(juce::ToggleButton::tickColourId, kAccent);
    setLookAndFeel(&lnf_);

    juce::StringArray keys, scales, inputs, shapes;
    for (int k = 0; k < 12; ++k) keys.add(keyName(k));
    for (int s = 0; s < int(Scale::Count); ++s) scales.add(scaleName(Scale(s)));
    for (int t = 0; t < int(InputType::Count); ++t) inputs.add(inputTypeName(InputType(t)));
    shapes = juce::StringArray{"Sine", "Square", "Sawtooth"};

    key_ = &addCombo(kKey, keys);
    scale_ = &addCombo(kScale, scales);
    input_ = &addCombo(kInputType, inputs);
    shape_ = &addCombo(kVibShape, shapes);

    retune_ = &addKnob(kRetune, true);
    humanize_ = &addKnob(kHumanize, true);
    flex_ = &addKnob(kFlexTune, true);
    natvib_ = &addKnob(kNaturalVibrato, true);
    tracking_ = &addKnob(kTracking);
    transpose_ = &addKnob(kTranspose);
    detune_ = &addKnob(kDetune);
    throat_ = &addKnob(kThroat);
    mix_ = &addKnob(kMix);
    vibRate_ = &addKnob(kVibRate);
    vibDelay_ = &addKnob(kVibDelay);
    vibOnset_ = &addKnob(kVibOnset);
    vibVar_ = &addKnob(kVibVariation);
    vibPitch_ = &addKnob(kVibPitch);
    vibAmp_ = &addKnob(kVibAmp);
    vibFormant_ = &addKnob(kVibFormant);

    formant_ = &addToggle(kFormant, "Formant");
    classic_ = &addToggle(kClassic, "Classic");
    lowLat_ = &addToggle(kLowLatency, "Low Latency");
    midi_ = &addToggle(kMidiTarget, "MIDI Notes");

    for (int n = 0; n < 12; ++n) {
        auto c = std::make_unique<Combo>();
        c->box.addItemList({"Scale", "Remove", "Bypass"}, 1);
        c->box.setTooltip(juce::String("Note ") + keyName(n));
        c->label.setText(keyName(n), juce::dontSendNotification);
        c->label.setJustificationType(juce::Justification::centred);
        addAndMakeVisible(c->box);
        addAndMakeVisible(c->label);
        c->att = std::make_unique<ComboAttachment>(proc_.apvts, paramDefs()[size_t(kNoteC + n)].id, c->box);
        notes_[size_t(n)] = std::move(c);
    }

    setResizable(true, true);
    setResizeLimits(760, 520, 1600, 1100);
    setSize(900, 600);
    startTimerHz(30);
}

LightTuneEditor::~LightTuneEditor() { setLookAndFeel(nullptr); }

LightTuneEditor::Knob& LightTuneEditor::addKnob(int id, bool big) {
    auto k = std::make_unique<Knob>();
    k->slider.setSliderStyle(juce::Slider::RotaryHorizontalVerticalDrag);
    k->slider.setTextBoxStyle(juce::Slider::TextBoxBelow, false, big ? 80 : 64, 16);
    k->label.setText(paramDefs()[size_t(id)].name, juce::dontSendNotification);
    k->label.setJustificationType(juce::Justification::centred);
    k->label.setFont(juce::Font(juce::FontOptions(big ? 14.f : 12.f)));
    addAndMakeVisible(k->slider);
    addAndMakeVisible(k->label);
    k->att = std::make_unique<SliderAttachment>(proc_.apvts, paramDefs()[size_t(id)].id, k->slider);
    knobs_.push_back(std::move(k));
    return *knobs_.back();
}

LightTuneEditor::Combo& LightTuneEditor::addCombo(int id, const juce::StringArray& items) {
    auto c = std::make_unique<Combo>();
    c->box.addItemList(items, 1);
    c->label.setText(paramDefs()[size_t(id)].name, juce::dontSendNotification);
    c->label.setFont(juce::Font(juce::FontOptions(12.f)));
    addAndMakeVisible(c->box);
    addAndMakeVisible(c->label);
    c->att = std::make_unique<ComboAttachment>(proc_.apvts, paramDefs()[size_t(id)].id, c->box);
    combos_.push_back(std::move(c));
    return *combos_.back();
}

LightTuneEditor::Toggle& LightTuneEditor::addToggle(int id, const juce::String& text) {
    auto t = std::make_unique<Toggle>();
    t->button.setButtonText(text);
    addAndMakeVisible(t->button);
    t->att = std::make_unique<ButtonAttachment>(proc_.apvts, paramDefs()[size_t(id)].id, t->button);
    toggles_.push_back(std::move(t));
    return *toggles_.back();
}

void LightTuneEditor::timerCallback() {
    auto& e = proc_.engine();
    const float inHz = e.inputPitchHz(), outHz = e.outputPitchHz();
    inHist_[size_t(histPos_)] = inHz > 0 ? float(69.0 + 12.0 * std::log2(inHz / 440.0)) : 0.f;
    outHist_[size_t(histPos_)] = outHz > 0 ? float(69.0 + 12.0 * std::log2(outHz / 440.0)) : 0.f;
    histPos_ = (histPos_ + 1) % kHistory;
    cents_ = 0.7f * cents_ + 0.3f * (inHz > 0 ? e.correctionCents() : 0.f);
    repaint(graphArea_.getUnion(meterArea_));
}

void LightTuneEditor::paint(juce::Graphics& g) {
    g.fillAll(kBg);
    g.setColour(kText);
    g.setFont(juce::Font(juce::FontOptions(22.f, juce::Font::bold)));
    g.drawText("LightTune", 16, 8, 200, 30, juce::Justification::centredLeft);
    g.setFont(juce::Font(juce::FontOptions(12.f)));
    g.setColour(kText.withAlpha(0.6f));
    g.drawText("latency " + juce::String(1000.0 * proc_.engine().latencySamples() / std::max(1.0, proc_.getSampleRate()), 1) + " ms",
               getWidth() - 160, 14, 144, 20, juce::Justification::centredRight);
    paintMeter(g, meterArea_);
    paintGraph(g, graphArea_);
}

void LightTuneEditor::paintMeter(juce::Graphics& g, juce::Rectangle<int> r) {
    g.setColour(kPanel);
    g.fillRoundedRectangle(r.toFloat(), 6.f);
    const float cx = float(r.getCentreX());
    g.setColour(kText.withAlpha(0.3f));
    g.drawVerticalLine(int(cx), float(r.getY() + 4), float(r.getBottom() - 4));
    const float c = juce::jlimit(-100.f, 100.f, cents_);
    const float w = c / 100.f * (float(r.getWidth()) * 0.5f - 6.f);
    g.setColour(kAccent2);
    if (w >= 0) g.fillRect(cx, float(r.getY() + 6), w, float(r.getHeight() - 12));
    else g.fillRect(cx + w, float(r.getY() + 6), -w, float(r.getHeight() - 12));
    g.setColour(kText.withAlpha(0.7f));
    g.setFont(juce::Font(juce::FontOptions(11.f)));
    g.drawText("-100", r.reduced(6, 0), juce::Justification::centredLeft);
    g.drawText("+100 cents", r.reduced(6, 0), juce::Justification::centredRight);
}

void LightTuneEditor::paintGraph(juce::Graphics& g, juce::Rectangle<int> r) {
    g.setColour(kPanel);
    g.fillRoundedRectangle(r.toFloat(), 6.f);
    // vertical range: centred on the recent pitch, +-7 semitones
    float sum = 0; int n = 0;
    for (float v : outHist_) if (v > 0) { sum += v; ++n; }
    const float centre = n ? std::round(sum / float(n)) : 60.f;
    const float lo = centre - 7.f, hi = centre + 7.f;
    auto y = [&](float m) { return float(r.getBottom()) - (m - lo) / (hi - lo) * float(r.getHeight()); };
    g.setFont(juce::Font(juce::FontOptions(10.f)));
    for (int m = int(std::ceil(lo)); m <= int(hi); ++m) {
        const float yy = y(float(m));
        g.setColour(kText.withAlpha((m % 12 == 0) ? 0.25f : 0.08f));
        g.drawHorizontalLine(int(yy), float(r.getX() + 28), float(r.getRight()));
        g.setColour(kText.withAlpha(0.5f));
        g.drawText(juce::String(keyName(m)) + juce::String(m / 12 - 1), r.getX() + 2, int(yy) - 6, 26, 12, juce::Justification::centredLeft);
    }
    auto drawCurve = [&](const std::array<float, kHistory>& h, juce::Colour col, float thick) {
        juce::Path path;
        bool pen = false;
        for (int i = 0; i < kHistory; ++i) {
            const float v = h[size_t((histPos_ + i) % kHistory)];
            const float x = float(r.getX() + 28) + float(i) / float(kHistory - 1) * float(r.getWidth() - 30);
            if (v <= 0 || v < lo - 2 || v > hi + 2) { pen = false; continue; }
            if (!pen) { path.startNewSubPath(x, y(v)); pen = true; }
            else path.lineTo(x, y(v));
        }
        g.setColour(col);
        g.strokePath(path, juce::PathStrokeType(thick));
    };
    drawCurve(inHist_, kAccent2.withAlpha(0.8f), 1.5f);
    drawCurve(outHist_, kAccent, 2.f);
    g.setColour(kText.withAlpha(0.7f));
    g.drawText("input", r.getRight() - 110, r.getY() + 4, 50, 14, juce::Justification::centredRight);
    g.setColour(kAccent);
    g.drawText("output", r.getRight() - 56, r.getY() + 4, 50, 14, juce::Justification::centredRight);
}

void LightTuneEditor::resized() {
    auto r = getLocalBounds().reduced(12);
    r.removeFromTop(36);

    // top row: key / scale / input type / toggles
    auto top = r.removeFromTop(46);
    auto placeCombo = [](Combo* c, juce::Rectangle<int> a) {
        c->label.setBounds(a.removeFromTop(16));
        c->box.setBounds(a.reduced(0, 2));
    };
    placeCombo(key_, top.removeFromLeft(70)); top.removeFromLeft(8);
    placeCombo(scale_, top.removeFromLeft(150)); top.removeFromLeft(8);
    placeCombo(input_, top.removeFromLeft(130)); top.removeFromLeft(12);
    top.removeFromTop(14);
    for (auto* t : {formant_, classic_, lowLat_, midi_}) t->button.setBounds(top.removeFromLeft(105));

    // note row
    r.removeFromTop(6);
    auto notes = r.removeFromTop(40);
    const int nw = notes.getWidth() / 12;
    for (int n = 0; n < 12; ++n) {
        auto a = notes.removeFromLeft(nw).reduced(2, 0);
        notes_[size_t(n)]->label.setBounds(a.removeFromTop(16));
        notes_[size_t(n)]->box.setBounds(a);
    }

    // big knobs
    r.removeFromTop(8);
    auto big = r.removeFromTop(130);
    const int bw = big.getWidth() / 4;
    for (auto* k : {retune_, humanize_, flex_, natvib_}) {
        auto a = big.removeFromLeft(bw).reduced(6, 0);
        k->label.setBounds(a.removeFromTop(18));
        k->slider.setBounds(a);
    }

    // meter + graph
    r.removeFromTop(6);
    meterArea_ = r.removeFromTop(22);
    r.removeFromTop(6);
    auto bottom = r.removeFromBottom(100);
    graphArea_ = r;

    // small knobs: pitch section + vibrato section
    std::vector<Knob*> small = {tracking_, transpose_, detune_, throat_, mix_, vibRate_, vibDelay_, vibOnset_, vibVar_, vibPitch_, vibAmp_, vibFormant_};
    bottom.removeFromTop(4);
    auto shapeArea = bottom.removeFromRight(110);
    placeCombo(shape_, shapeArea.removeFromTop(46).reduced(4, 0));
    const int sw = bottom.getWidth() / int(small.size());
    for (auto* k : small) {
        auto a = bottom.removeFromLeft(sw).reduced(2, 0);
        k->label.setBounds(a.removeFromTop(28));
        k->label.setFont(juce::Font(juce::FontOptions(11.f)));
        k->label.setMinimumHorizontalScale(0.6f);
        k->slider.setBounds(a);
    }
}
