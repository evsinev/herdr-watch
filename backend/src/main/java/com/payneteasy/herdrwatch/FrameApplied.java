package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.model.Model.HostState;

/**
 * Внутреннее CDI-событие: Registry применил CONNECTED-кадр хоста. Несёт прежнее и
 * новое состояние, чтобы наблюдатели (напр. Telegram-оповещения) могли надёжно
 * диффить статусы агентов. Не сериализуется, наружу не отдаётся.
 */
public record FrameApplied(HostState prev, HostState now) {}
