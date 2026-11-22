package by.csl.backupcontrol.domain.model

enum class BackupStatus {
    SUCCESS,
    WARNING,
    ERROR,
    UNKNOWN;

    val labelRu: String
        get() = when (this) {
            SUCCESS -> "Успешно"
            WARNING -> "Предупреждение"
            ERROR -> "Ошибка"
            UNKNOWN -> "Не определён"
        }
}

enum class OrganizationHealth {
    ERROR,
    WARNING,
    MISSING_MAIL,
    HEALTHY,
    IGNORED
}

enum class DashboardFilter(val labelRu: String) {
    ALL("Все"),
    PROBLEMS("Проблемы"),
    ERRORS("Ошибки"),
    MISSING("Нет писем"),
    HEALTHY("В норме"),
    IGNORED("Скрытые")
}
