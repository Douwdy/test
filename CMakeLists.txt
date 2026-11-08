cmake_minimum_required(VERSION 3.22)
project(LightTune VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(LIGHTTUNE_BUILD_CLI   "Build the command-line processor" ON)
option(LIGHTTUNE_BUILD_TESTS "Build the objective tests"        ON)
option(LIGHTTUNE_BUILD_CLAP  "Build the CLAP plugin"            ON)
option(LIGHTTUNE_BUILD_JUCE  "Build VST3/AU/Standalone with JUCE (downloads JUCE)" OFF)

include(FetchContent)

# ---------------------------------------------------------------- DSP core
add_library(lighttune_core INTERFACE)
target_include_directories(lighttune_core INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/core/include)
if(MSVC)
  target_compile_options(lighttune_core INTERFACE /fp:fast)
else()
  target_compile_options(lighttune_core INTERFACE -O2 -fno-math-errno)
endif()

if(LIGHTTUNE_BUILD_CLI)
  add_executable(lighttune cli/lighttune_cli.cpp)
  target_link_libraries(lighttune PRIVATE lighttune_core)
endif()

if(LIGHTTUNE_BUILD_TESTS)
  enable_testing()
  add_executable(lighttune_tests tests/test_engine.cpp)
  target_link_libraries(lighttune_tests PRIVATE lighttune_core)
  add_test(NAME engine COMMAND lighttune_tests)
  if(LIGHTTUNE_BUILD_CLAP AND UNIX)
    add_executable(lighttune_clap_host_test tests/test_clap_host.cpp)
    target_link_libraries(lighttune_clap_host_test PRIVATE ${CMAKE_DL_LIBS})
  endif()
endif()

# ---------------------------------------------------------------- CLAP
if(LIGHTTUNE_BUILD_CLAP)
  FetchContent_Declare(clap GIT_REPOSITORY https://github.com/free-audio/clap.git GIT_TAG 1.2.9 GIT_SHALLOW TRUE)
  FetchContent_GetProperties(clap)
  if(NOT clap_POPULATED)
    FetchContent_Populate(clap)
  endif()
  add_library(lighttune_clap MODULE plugins/clap/lighttune_clap.cpp)
  target_include_directories(lighttune_clap PRIVATE ${clap_SOURCE_DIR}/include)
  target_link_libraries(lighttune_clap PRIVATE lighttune_core)
  set_target_properties(lighttune_clap PROPERTIES PREFIX "" SUFFIX ".clap" OUTPUT_NAME "LightTune"
                        CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
  if(APPLE)
    set_target_properties(lighttune_clap PROPERTIES BUNDLE TRUE BUNDLE_EXTENSION clap
                          MACOSX_BUNDLE_GUI_IDENTIFIER com.lighttune.pitchcorrector)
  endif()
  if(LIGHTTUNE_BUILD_TESTS AND UNIX)
    target_include_directories(lighttune_clap_host_test PRIVATE ${clap_SOURCE_DIR}/include)
    add_dependencies(lighttune_clap_host_test lighttune_clap)
    add_test(NAME clap_host COMMAND lighttune_clap_host_test $<TARGET_FILE:lighttune_clap>)
  endif()
endif()

# ---------------------------------------------------------------- JUCE (VST3 / AU / Standalone)
if(LIGHTTUNE_BUILD_JUCE)
  FetchContent_Declare(juce GIT_REPOSITORY https://github.com/juce-framework/JUCE.git GIT_TAG 8.0.9 GIT_SHALLOW TRUE)
  FetchContent_MakeAvailable(juce)

  set(LT_FORMATS VST3 Standalone)
  if(APPLE)
    list(APPEND LT_FORMATS AU)
  endif()

  juce_add_plugin(LightTunePlugin
    PRODUCT_NAME "LightTune"
    COMPANY_NAME "LightTune"
    BUNDLE_ID com.lighttune.pitchcorrector
    PLUGIN_MANUFACTURER_CODE Ltun
    PLUGIN_CODE Ltn1
    FORMATS ${LT_FORMATS}
    IS_SYNTH FALSE
    NEEDS_MIDI_INPUT TRUE
    NEEDS_MIDI_OUTPUT FALSE
    IS_MIDI_EFFECT FALSE
    EDITOR_WANTS_KEYBOARD_FOCUS FALSE
    VST3_CATEGORIES Fx Pitch Shift
    AU_MAIN_TYPE kAudioUnitType_MusicEffect
    COPY_PLUGIN_AFTER_BUILD FALSE)

  target_sources(LightTunePlugin PRIVATE
    plugins/juce/PluginProcessor.cpp
    plugins/juce/PluginEditor.cpp)

  target_compile_definitions(LightTunePlugin PUBLIC
    JUCE_WEB_BROWSER=0 JUCE_USE_CURL=0 JUCE_VST3_CAN_REPLACE_VST2=0 JUCE_DISPLAY_SPLASH_SCREEN=0)

  target_link_libraries(LightTunePlugin
    PRIVATE lighttune_core juce::juce_audio_utils
    PUBLIC juce::juce_recommended_config_flags juce::juce_recommended_lto_flags juce::juce_recommended_warning_flags)
endif()
