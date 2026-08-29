import type {
  ClaudeUsage,
  ClaudeUsageModelWindow,
  ClaudeUsageSource,
  ClaudeUsageWindow,
} from "@/lib/types";
import { hex, usageColor } from "@/lib/theme";
import { cn } from "@/lib/utils";

/** «через 38m» / «через 2h 10m» — сколько осталось до сброса окна. */
function untilReset(resetsAt: number): string {
  const secs = Math.round(resetsAt - Date.now() / 1000);
  if (secs <= 0) return "resetting";
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ${mins % 60}m`;
  return `${Math.round(hours / 24)}d`;
}

/** «3m ago» — возраст показаний: цифра без возраста здесь вводит в заблуждение. */
function ago(capturedAt: number): string {
  const secs = Math.max(0, Math.round(Date.now() / 1000 - capturedAt));
  if (secs < 60) return `${secs}s ago`;
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

/**
 * Кто наблюдал показания — подпись рядом с их возрастом. «34%, 3m ago» значит
 * разное в зависимости от источника: statusline двигается только пока открыта
 * сессия Claude Code, аккаунт-API видит и расход с других машин, но ходит по
 * интервалу. Незнакомое имя источника показываем как есть, а не прячем.
 */
function sourceLabel(source: ClaudeUsageSource): string | null {
  switch (source) {
    case "STATUSLINE":
      return "statusline";
    case "ACCOUNT_API":
      return "account api";
    case "NONE":
      return null;
    default:
      return source.toLowerCase().replace(/_/g, " ");
  }
}

/** Есть ли что показывать: NOT_CONFIGURED и «ни одно окно не отчиталось» рисовать нечем. */
export function hasUsage(usage: ClaudeUsage | null): usage is ClaudeUsage {
  return (
    !!usage &&
    usage.state !== "NOT_CONFIGURED" &&
    !!(usage.windows.fiveHour || usage.windows.sevenDay)
  );
}

/** Худшая полоса среди отчитавшихся окон — ей красится плитка целиком. */
function worstColor(usage: ClaudeUsage): string {
  const { fiveHour, sevenDay } = usage.windows;
  return usageColor(Math.max(fiveHour?.usedPercent ?? 0, sevenDay?.usedPercent ?? 0));
}

function windowRows(usage: ClaudeUsage): { label: string; w: ClaudeUsageWindow }[] {
  const { fiveHour, sevenDay } = usage.windows;
  const rows: { label: string; w: ClaudeUsageWindow }[] = [];
  if (fiveHour) rows.push({ label: "5h", w: fiveHour });
  if (sevenDay) rows.push({ label: "7d", w: sevenDay });
  return rows;
}

/**
 * Плитка квоты для Compact — последняя в сетке, наравне с карточками агентов.
 * Цифры здесь крупные: экран рассчитан на взгляд издалека, а мелкий индикатор в
 * тулбаре с этой дистанции не читался. Размеры шрифта приходят снаружи — те же,
 * которыми Compact масштабирует карточки агентов под размер ячейки.
 *
 * Источник показаний подписан рядом с их возрастом — как в {@link UsageGauge}.
 *
 * Помодельные окна здесь — ОДНА строка внизу («fable 14% · opus 3%»), а не список с
 * полосками, как в Monitor. Плитку читают через комнату, и она делит высоту ячейки с
 * карточками агентов: строка на модель отжала бы место у 5h/7d — единственных окон,
 * которые тебя реально останавливают. Разбивка информационная, ей хватает подписи.
 * Пустой models не рисует ничего — под дефолтным push плитка выглядит как раньше.
 */
export function UsageTile({
  usage,
  full,
  fill,
  nameSize,
  hostSize,
  taskSize,
}: {
  usage: ClaudeUsage;
  full: boolean;
  fill: boolean;
  nameSize: number;
  hostSize: number;
  taskSize: number;
}) {
  const stale = usage.state === "STALE";
  const color = worstColor(usage);
  const rows = windowRows(usage);
  const source = sourceLabel(usage.source);

  const metaStyle = fill ? { fontSize: hostSize } : undefined;
  const pctStyle = fill ? { color, fontSize: nameSize } : { color };
  const subStyle = fill ? { fontSize: taskSize } : undefined;

  return (
    <div
      className={cn(
        "flex flex-col gap-2 overflow-hidden rounded-[10px]",
        full ? "h-full min-h-0" : "h-32",
      )}
      style={{
        padding: "14px 16px",
        background: hex(color, 0.1),
        border: `1px solid ${hex(color, 0.28)}`,
        opacity: stale ? 0.55 : 1,
      }}
      title={stale ? (usage.error ?? "stale reading") : undefined}
    >
      <div className="flex shrink-0 items-baseline gap-2">
        <span className="font-mono text-[12px] text-muted" style={metaStyle}>
          claude · account
        </span>
        <span className="ml-auto font-mono text-[11px] text-muted-3" style={metaStyle}>
          {stale && "stale · "}
          {source && `${source} · `}
          {usage.capturedAt != null ? ago(usage.capturedAt) : "no reading"}
        </span>
      </div>

      <div className="flex min-h-0 flex-1 flex-col justify-center gap-2">
        {rows.map(({ label, w }) => (
          <div key={label} className="flex flex-col gap-1">
            <div className="flex items-baseline gap-2">
              <span className="font-mono text-[12px] text-muted" style={subStyle}>
                {label}
              </span>
              <span
                className="font-mono text-[26px] font-bold leading-none"
                style={pctStyle}
              >
                {w.usedPercent}%
              </span>
              <span className="ml-auto font-mono text-[11px] text-muted-3" style={subStyle}>
                {untilReset(w.resetsAt)}
              </span>
            </div>
            <span
              className="relative block h-[4px] overflow-hidden rounded-full"
              style={{ background: hex(color, 0.15) }}
            >
              <span
                className="absolute inset-y-0 left-0 rounded-full"
                style={{
                  width: `${Math.min(100, Math.max(0, w.usedPercent))}%`,
                  background: usageColor(w.usedPercent),
                }}
              />
            </span>
          </div>
        ))}
      </div>

      {usage.models.length > 0 && (
        <span
          className="shrink-0 truncate font-mono text-[11px] text-muted-3"
          style={subStyle}
          title={usage.models.map((m) => `${m.model} ${m.usedPercent}%`).join(" · ")}
        >
          {usage.models.map((m) => `${m.model.toLowerCase()} ${m.usedPercent}%`).join(" · ")}
        </span>
      )}
    </div>
  );
}

function Bar({ w, label }: { w: ClaudeUsageWindow; label: string }) {
  const color = usageColor(w.usedPercent);
  return (
    <div className="flex items-center gap-2">
      <span className="w-5 shrink-0 font-mono text-[11px] text-muted-2">{label}</span>
      <span
        className="relative h-[5px] flex-1 overflow-hidden rounded-full"
        style={{ background: hex(color, 0.15) }}
      >
        <span
          className="absolute inset-y-0 left-0 rounded-full"
          style={{ width: `${Math.min(100, Math.max(0, w.usedPercent))}%`, background: color }}
        />
      </span>
      <span className="w-9 shrink-0 text-right font-mono text-[11px]" style={{ color }}>
        {w.usedPercent}%
      </span>
      <span className="w-16 shrink-0 text-right font-mono text-[10.5px] text-muted-3">
        {untilReset(w.resetsAt)}
      </span>
    </div>
  );
}

/**
 * Помодельное недельное окно. Намеренно подчинено полосам 5h/7d: это разбивка,
 * а не третье окно, которое тебя останавливает. Отсюда — уже полоска, тусклее
 * подпись и никакого времени сброса (оно то же недельное, что и у 7d).
 */
function ModelRow({ m }: { m: ClaudeUsageModelWindow }) {
  const color = usageColor(m.usedPercent);
  return (
    <div className="flex items-center gap-2">
      <span className="w-5 shrink-0" />
      <span className="min-w-0 flex-1 truncate font-mono text-[10px] text-muted-3">
        {m.model}
      </span>
      <span
        className="relative h-[3px] w-16 shrink-0 overflow-hidden rounded-full"
        style={{ background: hex(color, 0.12) }}
      >
        <span
          className="absolute inset-y-0 left-0 rounded-full"
          style={{
            width: `${Math.min(100, Math.max(0, m.usedPercent))}%`,
            background: hex(color, 0.6),
          }}
        />
      </span>
      <span className="w-9 shrink-0 text-right font-mono text-[10px] text-muted-3">
        {m.usedPercent}%
      </span>
      <span className="w-16 shrink-0" />
    </div>
  );
}

/**
 * Квота подписки Claude для карточки хоста (Monitor). В Compact своя форма —
 * {@link UsageTile}: там сетка из одинаковых плиток и цифры нужны крупные.
 *
 * Показывается ТОЛЬКО на карточке локального хоста и всегда с явной подписью
 * «account» — цифры относятся к аккаунту Claude, а не к машине, и спутать эти
 * вещи нельзя.
 *
 * Не рендерится вовсе при NOT_CONFIGURED (хук не установлен — это не ошибка).
 * STALE отличается визуально (приглушённый) и всё равно показывает возраст: цифры
 * двигаются только пока открыта сессия Claude Code, поэтому запись может быть старой.
 */
export function UsageGauge({ usage }: { usage: ClaudeUsage | null }) {
  if (!hasUsage(usage)) return null;

  const { fiveHour, sevenDay } = usage.windows;
  const stale = usage.state === "STALE";
  const source = sourceLabel(usage.source);

  return (
    <div
      className="flex flex-col gap-1.5"
      style={{ opacity: stale ? 0.55 : 1 }}
      title={stale ? (usage.error ?? "stale reading") : undefined}
    >
      <div className="flex items-baseline gap-1.5">
        <span className="font-mono text-[10px] uppercase text-muted-2">
          claude quota · account
        </span>
        <span className="ml-auto font-mono text-[10px] text-muted-3">
          {stale && "stale · "}
          {source && `${source} · `}
          {usage.capturedAt != null ? ago(usage.capturedAt) : "no reading"}
        </span>
      </div>
      {fiveHour && <Bar w={fiveHour} label="5h" />}
      {sevenDay && <Bar w={sevenDay} label="7d" />}
      {usage.models.map((m) => (
        <ModelRow key={m.model} m={m} />
      ))}
    </div>
  );
}
