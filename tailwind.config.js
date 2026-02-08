/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./app/**/*.{js,jsx,ts,tsx}",
    "./components/**/*.{js,jsx,ts,tsx}",
  ],
  presets: [require("nativewind/preset")],
  theme: {
    extend: {
      colors: {
        tavern: {
          bg: "#1a1a2e",
          card: "#16213e",
          accent: "#e94560",
          gold: "#f5a623",
          text: "#eaeaea",
          muted: "#8b8b9e",
        },
        tier: {
          s: "#ff6b6b",
          a: "#ffa502",
          b: "#2ed573",
          c: "#1e90ff",
          d: "#a4a4a4",
        },
      },
    },
  },
  plugins: [],
};
