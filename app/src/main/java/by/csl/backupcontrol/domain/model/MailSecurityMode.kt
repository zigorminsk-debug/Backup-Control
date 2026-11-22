package by.csl.backupcontrol.domain.model

enum class MailSecurityMode(val defaultPort: Int, val titleRu: String) {
    SSL_TLS(993, "SSL/TLS (порт 993)"),
    STARTTLS(143, "STARTTLS (порт 143)"),
    PLAIN(143, "Без шифрования (порт 143)")
}
