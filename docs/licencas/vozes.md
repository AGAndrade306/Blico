# Vozes neurais

As vozes não vão no APK: o app baixa cada pacote da release `tts-models` do [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) quando a pessoa pede.

| Voz | Modelo | Licença |
| --- | --- | --- |
| Dora, Alex, Santa | Kokoro-82M v1.0 (int8, multilíngue) | Apache 2.0 |
| Faber, Cadu, Jeff | Piper pt_BR | CC0 |
| Dii, Miro | Piper pt_BR (OpenVoiceOS) | CC BY-NC-SA 4.0: uso pessoal, não comercial |

O motor é o sherpa-onnx 1.13.8 (Apache 2.0), com o espeak-ng para a pronúncia do português (GPL 3, incluído nos pacotes de voz).
