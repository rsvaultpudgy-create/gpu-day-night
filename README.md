# GPU Day Night

A GPU renderer for Old School RuneScape that brings a living day-night cycle to the
world. Built on RuneLite's GPU plugin, it adds dynamic lighting and a fully animated
sky on top of the hardware renderer.

## Features

- **Smooth day-night cycle** — the world gradually darkens and cools into night and
  brightens back into day, on a real-time or configurable cycle.
- **Procedural animated sky** — a deep-space night sky with a layered Milky Way,
  dust lanes, and faint nebulosity.
- **Twinkling stars** — multiple star layers, each twinkling at its own rate, with
  subtle colour variation and brighter feature stars.
- **Shooting stars** — meteors streak across the night sky every so often.
- **Daytime clouds** — drifting procedural clouds during the day.
- **Player light** — a warm glow around your character at night that lights up the
  ground (and the ocean while sailing).

## Usage

GPU Day Night is a GPU renderer, so enable it in place of the stock **GPU** plugin
(only one GPU renderer can be active at a time). Day-night cycle, sky, and player
light each have their own toggles and sliders in the plugin config.

## Credits / Legal

This plugin is built on RuneLite's **GPU plugin**, Copyright (c) 2018
Adam &lt;Adam@sigterm.info&gt;, used under the BSD 2-Clause License. All original
copyright notices from the GPU plugin are retained in the source files.

The day-night cycle, procedural sky, and player lighting are
Copyright (c) 2026 Pudgy, also released under the BSD 2-Clause License (see `LICENSE`).
