package app.blico.core

/** Classes de risco 0 a 3. Decidem se uma chamada executa direto, pede confirmação ou é recusada. */
enum class RiskClass {
    /** 0. Leitura e abertura: executa direto. */
    READ,

    /** 1. Preparar: executa e para antes do passo final. */
    PREPARE,

    /** 2. Compromisso: só executa depois do toque no cartão Confirmar. */
    COMMIT,

    /** 3. Sensível: recusado por padrão. */
    SENSITIVE,
}
