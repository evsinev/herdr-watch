package com.payneteasy.herdrwatch.usage;

/**
 * Единое правило приведения утилизации к целым процентам — общее для обоих
 * источников. Иначе push и pull разошлись бы не по данным, а по соглашению об
 * округлении, и расхождение выглядело бы как настоящее.
 */
public final class Percents {

    private Percents() {}

    /**
     * Целый процент, либо null если значение непригодно.
     *
     * <p>Отрицательные отбрасываем; выше 100 клампим, а не отбрасываем — перерасход
     * не должен гасить индикатор ровно тогда, когда он важнее всего.
     */
    public static Integer toWhole(double raw) {
        if (!Double.isFinite(raw) || raw < 0) return null;
        return (int) Math.min(100, Math.round(raw));
    }
}
