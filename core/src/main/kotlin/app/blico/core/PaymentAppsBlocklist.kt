package app.blico.core

/**
 * Apps bancários e de pagamento que o Blico nunca automatiza. Fixa no código de propósito:
 * não é configurável pelo modelo nem por skills.
 *
 * Lista inicial; conferir os pacotes instalados no aparelho e completar.
 */
object PaymentAppsBlocklist {
    val packages: Set<String> = setOf(
        "com.nu.production", // Nubank
        "com.itau", // Itaú
        "br.com.bb.android", // Banco do Brasil
        "com.bradesco", // Bradesco
        "com.santander.app", // Santander
        "br.com.gabba.Caixa", // Caixa
        "br.com.intermedium", // Inter
        "com.c6bank.app", // C6
        "com.picpay", // PicPay
        "com.mercadopago.wallet", // Mercado Pago
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.samsung.android.spay", // Samsung Wallet
    )
}
