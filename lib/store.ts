import { create } from "zustand";
import { type ChatMessage } from "./claude";

interface AppState {
  messages: ChatMessage[];
  isLoading: boolean;
  apiKey: string;
  overlayActive: boolean;
  addMessage: (message: ChatMessage) => void;
  setLoading: (loading: boolean) => void;
  setApiKey: (key: string) => void;
  clearMessages: () => void;
  setOverlayActive: (active: boolean) => void;
}

export const useAppStore = create<AppState>((set) => ({
  messages: [],
  isLoading: false,
  apiKey: "",
  overlayActive: false,

  addMessage: (message) =>
    set((state) => ({ messages: [...state.messages, message] })),

  setLoading: (loading) => set({ isLoading: loading }),

  setApiKey: (key) => set({ apiKey: key }),

  clearMessages: () => set({ messages: [] }),

  setOverlayActive: (active) => set({ overlayActive: active }),
}));
