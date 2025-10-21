# **DK’s Minigames (Paper 1.21.x)**

**Type:** Minecraft Plugin · **Tech Stack:** Java (Paper API) · **Status:** Completed

## Overview

A Paper 1.21.x plugin that revives classic "[Hive](https://playhive.com/)"-style minigames (e.g., **Block Party**, **Electric Floor**) with lightweight setup, sign joins, and power-ups.

## Features

* Multiple games with shared infrastructure (arenas, lobbies, timers, scoring).
* **config.yml** driven arenas and spawn points for zero-code deployment.
* Join/leave **signs** and in-game **power-ups**.
* Command suite per game with a small debug toolbox.

## Setup

1. Drop the JAR into `plugins/` and restart.
2. Edit **`config.yml`** to define arenas, lobby, and per-game spawns.
3. Reload or restart; arenas auto-register on load.

## Commands (from `plugin.yml`)

* `/ping` — replies with Pong + latency.
* `/debug` — debug tools (incl. spawning power-ups); **permission:** `dks.debug`.
* `/blockparty <start|end|join|leave|players|highscore|...>` (**alias:** `/bp`).
* `/electricfloor <start|end|join|leave|players|highscore|...>` (**alias:** `/ef`).

## Signs

* Line 1: `[<GameName>]` (e.g., `[BlockParty]`, `[ElectricFloor]`).
* Line 2: `<ACTION>` as implemented (e.g., `JOIN`, `LEAVE`, `START`, `END`).
* Place the sign in a lobby; players interact to trigger the action.

## Power-ups

* Built-in power-up system; trigger via gameplay or `/debug powerup [x y z]` for testing.

###Extending (add a new game)

1. **Extend the abstract `Minigame` class.**
2. Implement the core lifecycle and hooks you need (player join/leave, start/stop, tick/round logic, win/lose, scoreboard).
3. Provide a **Map/Arena** descriptor (spawns, regions, rules).
4. Register commands and (optionally) sign actions.
5. Add the command block to `plugin.yml` and wire events via the Paper API.
6. Reference your arena in **`config.yml`**; the framework handles loading and state.

## Permissions

* `dks.debug` — default `op` (for testing/admin utilities).
