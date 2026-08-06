# Model Card & Provenance: "Hey Gotcha" Custom Wake Word

## 1. Recommended Activation Thresholds

| Use Case | Threshold (`0.0 - 1.0`) | Notes / Behavior |
| :--- | :--- | :--- |
| **Balanced / Standard** *(Recommended)* | **`0.50`** | Best balance between high responsiveness and zero false activations. |
| **High Precision / Loud Environments** | **`0.65`** | Requires clear speech; virtually eliminates false triggers in TV/music environments. |
| **High Sensitivity / Far-Field** | **`0.35`** | Useful for distant microphones (> 3-5 meters away). |

### Integration Code Snippet
```python
from openwakeword.model import Model

# Load custom model
oww = Model(wakeword_models=["hey_gotcha.onnx"], inference_framework="onnx")

THRESHOLD = 0.50

# In your audio streaming loop:
prediction = oww.predict(audio_frame_16k_int16)
if prediction["hey_gotcha"] >= THRESHOLD:
    print("Wake word 'Hey Gotcha' detected!")
```

---

## 2. Model Provenance & Licensing Metadata

### Backbone & Architecture
- **Framework**: `openWakeWord` (v0.5.1)
- **Model Backbone**: Binary Deep Neural Network (DNN) trained on openWakeWord 16-frame spectrogram embeddings.
- **Backbone License**: **Apache-2.0**

### Data Provenance
- **Positive Training Data**: 
  - **Source**: 30,000 synthetic audio clips generated via **Piper TTS** using the `en_US-libritts_r-medium` voice model.
  - **Target Phrases**: `["hey_gotcha", "hey_gah_chuh"]`
  - **License**: CC-BY-4.0 / Public Domain synthetic speech output.
- **Negative & Background Data Sources**:
  - **ACAV100M Dataset**: Pre-computed 2,000-hour audio feature representations from YouTube/AudioSet (Creative Commons / Open Access).
  - **Room Impulse Responses**: MIT Environmental Impulse Responses (`mit_rirs`).
  - **Background Noise**: AudioSet 16kHz background noise and FMA music.

### Final Model License
- **Distribution License**: **Apache-2.0** (Fully compatible with commercial and open-source applications).
