/** @vitest-environment jsdom */
import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, beforeEach, afterEach } from "vitest";
import { ThemeProvider, useTheme } from "../client/src/contexts/ThemeContext";

function ThemeProbe() {
  const { theme, toggleTheme, highContrast, toggleHighContrast, reduceMotion, toggleReduceMotion, largeText, toggleLargeText, simpleMode, toggleSimpleMode } = useTheme();
  return <><button type="button" onClick={toggleTheme}>Tema: {theme}</button><button type="button" onClick={toggleHighContrast}>Contrasto: {String(highContrast)}</button><button type="button" onClick={toggleReduceMotion}>Movimento: {String(reduceMotion)}</button><button type="button" onClick={toggleLargeText}>Testo: {String(largeText)}</button><button type="button" onClick={toggleSimpleMode}>Semplice: {String(simpleMode)}</button></>;
}

describe("ThemeProvider", () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.className = "";
  });
  afterEach(() => {
    cleanup();
    localStorage.clear();
    document.documentElement.className = "";
  });

  it("applica e persiste il tema scuro solo dopo un’azione dell’utente", () => {
    const initialRender = render(<ThemeProvider defaultTheme="light" switchable><ThemeProbe /></ThemeProvider>);

    expect(screen.getByRole("button", { name: "Tema: light" })).toBeTruthy();
    expect(document.documentElement.classList.contains("dark")).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Tema: light" }));

    expect(screen.getByRole("button", { name: "Tema: dark" })).toBeTruthy();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(localStorage.getItem("theme")).toBe("dark");

    initialRender.unmount();
    document.documentElement.className = "";
    render(<ThemeProvider defaultTheme="light" switchable><ThemeProbe /></ThemeProvider>);

    expect(screen.getByRole("button", { name: "Tema: dark" })).toBeTruthy();
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("persiste contrasto elevato e riduzione movimento senza modificare il tema", () => {
    const initialRender = render(<ThemeProvider defaultTheme="light" switchable><ThemeProbe /></ThemeProvider>);
    fireEvent.click(screen.getByRole("button", { name: "Contrasto: false" }));
    fireEvent.click(screen.getByRole("button", { name: "Movimento: false" }));
    fireEvent.click(screen.getByRole("button", { name: "Testo: false" }));
    fireEvent.click(screen.getByRole("button", { name: "Semplice: false" }));
    expect(document.documentElement.classList.contains("binario-high-contrast")).toBe(true);
    expect(document.documentElement.classList.contains("binario-reduce-motion")).toBe(true);
    expect(localStorage.getItem("binario-high-contrast")).toBe("true");
    expect(localStorage.getItem("binario-reduce-motion")).toBe("true");
    expect(localStorage.getItem("binario-large-text")).toBe("true");
    expect(localStorage.getItem("binario-simple-mode")).toBe("true");
    initialRender.unmount();
    document.documentElement.className = "";
    render(<ThemeProvider defaultTheme="light" switchable><ThemeProbe /></ThemeProvider>);
    expect(screen.getByRole("button", { name: "Contrasto: true" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Movimento: true" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Testo: true" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Semplice: true" })).toBeTruthy();
  });
});
