package com.payneteasy.herdrwatch.source;

/**
 * Источник состояния одного хоста: живёт в своём (виртуальном) потоке, пишет кадры
 * в Registry и умеет мгновенно останавливаться. Реализации: {@link SshSource}
 * (удалённый хост по ssh) и {@link LocalSource} (локальный herdr без ssh).
 */
public interface Source extends Runnable {
    /** Мгновенно и чисто остановить источник (для hot-remove/hot-edit). */
    void stop();
}
