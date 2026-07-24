import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";

// Шрифты из макета: IBM Plex Sans (проза/лейблы, вкл. кириллицу) и
// JetBrains Mono (id/пути/ветки/статусы). Каждый .css содержит @font-face
// по всем сабсетам (latin + cyrillic), браузер подхватит нужный по unicode-range.
import "@fontsource/ibm-plex-sans/400.css";
import "@fontsource/ibm-plex-sans/500.css";
import "@fontsource/ibm-plex-sans/600.css";
import "@fontsource/ibm-plex-sans/700.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "@fontsource/jetbrains-mono/700.css";

import "./index.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
