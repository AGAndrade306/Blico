# O LiteRT-LM chama classes Kotlin a partir do código nativo (JNI) e lê anotações por reflexão.
-keep class com.google.ai.edge.litertlm.** { *; }

# sherpa-onnx (voz neural): o código nativo lê e cria estas classes pelo nome.
-keep class com.k2fsa.sherpa.onnx.** { *; }
