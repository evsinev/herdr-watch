package com.payneteasy.herdrwatch.model;

/**
 * Способ получения данных herdr для конкретного хоста.
 *
 * <ul>
 *   <li>{@link #COMMAND} — spawn CLI herdr (bash/ssh + jq), как исторически. Дефолт и fallback.</li>
 *   <li>{@link #SOCKET}  — прямое подключение к unix-сокету herdr (socket API, NDJSON), без subprocess/jq.</li>
 * </ul>
 */
public enum DataSource {
    COMMAND,
    SOCKET;

    /** Лениво распарсить значение из REST/JSON: null/пустое/неизвестное → {@link #COMMAND}. */
    public static DataSource parse(String s) {
        if (s == null || s.isBlank()) return COMMAND;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return COMMAND;
        }
    }
}
