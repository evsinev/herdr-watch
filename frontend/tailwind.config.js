import tailwindcssAnimate from "tailwindcss-animate";

/** @type {import('tailwindcss').Config} */
// Тёмная палитра и шрифты из утверждённого макета (design/). Динамические
// цвета статусов/health считаются в рантайме (src/lib/theme.ts), а структурные
// цвета/типографика заданы здесь как токены.
export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        page: "#0b0d10",
        card: "#14171d",
        field: "#0e1116",
        accent: "#378ADD",
        line: "rgba(255,255,255,0.08)",
        "line-soft": "rgba(255,255,255,0.05)",
        "line-strong": "rgba(255,255,255,0.14)",
        // текст (от яркого к самому тусклому)
        ink: "#e6e8ec",
        "ink-2": "#c8ccd2",
        "ink-3": "#a4a9b2",
        muted: "#7a808a",
        "muted-2": "#5a5f68",
        "muted-3": "#4c515a",
        focus: "#d0a24a",
        danger: "#E24B4A",
      },
      fontFamily: {
        sans: ['"IBM Plex Sans"', "system-ui", "sans-serif"],
        mono: ['"JetBrains Mono"', "ui-monospace", "monospace"],
      },
      maxWidth: {
        grid: "1600px",
      },
    },
  },
  plugins: [tailwindcssAnimate],
};
