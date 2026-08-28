package com.payneteasy.herdrwatch.usage.pull;

/**
 * Итог поиска креденшла. «Не настроено» и «не авторизовано» разведены намеренно
 * (спека): оператор должен отличать «я это не включал» от «включил, но не работает».
 */
public record CredentialLookup(Status status, ClaudeCredential credential, String detail) {

    public enum Status {
        /** Найден живой токен с нужным скоупом. */
        FOUND,
        /** Ни одного пригодного креденшла — нормальное состояние до настройки. */
        NOT_CONFIGURED,
        /** Креденшл есть, но без user:profile — эндпоинт ответит 403. */
        NOT_AUTHORIZED,
        /** Хранилище отказало в доступе (macOS может спросить разрешение). */
        ACCESS_DENIED
    }

    public static CredentialLookup found(ClaudeCredential c) {
        return new CredentialLookup(Status.FOUND, c, null);
    }

    public static CredentialLookup notConfigured(String detail) {
        return new CredentialLookup(Status.NOT_CONFIGURED, null, detail);
    }

    public static CredentialLookup notAuthorized(String detail) {
        return new CredentialLookup(Status.NOT_AUTHORIZED, null, detail);
    }

    public static CredentialLookup accessDenied(String detail) {
        return new CredentialLookup(Status.ACCESS_DENIED, null, detail);
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }

    /** Диагностика для логов — без токена. */
    public String summary() {
        return detail == null ? status.name() : status + ": " + detail;
    }
}
