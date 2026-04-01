# Game Assistant Plus

![LSPosed Module](https://img.shields.io/badge/LSPosed-Module-red?style=for-the-badge)
![App](https://img.shields.io/badge/App-GAP-blue?style=for-the-badge)

## Description

This is the Xposed side of the Lenovo Super Resolution port.

It patches Lenovo Game Assistant at runtime and includes **GAP** (`Game Assistant Plus`), the app used to manage which games are enabled for Super Resolution / Frame Interpolation.

It also keeps Lenovo's Game Helper resource metadata aligned with the rebuilt package, widens selected region-gated feature paths where needed.

This module is meant to be used together with **LSRPort**.

---

## Requirements

1. **Root**
2. **[LSPosed](https://github.com/JingMatrix/Vector)**
3. **[LSRPort](https://gitlab.com/miner7222/lsrport)**

---

## Installation

1. Install the `GAP` APK.
2. Enable the module in **LSPosed**.
3. Reboot the device.
4. Open **GAP**.
5. Grant root access when asked.
6. Select the games you want to enable and press **Save**.

---

## Usage

- Open **GAP** if you want to add or remove supported games.
- After saving, GAP updates the runtime whitelist, syncs it to `Settings.Global`, restarts `gppservice`, and force-stops `com.zui.game.service` so changes apply immediately.
- Launch your game and use Game Assistant's **Ultra HD Vision** toggle in the sidebar.

---

## Troubleshooting

- If the **Ultra HD Vision** toggle does not appear, make sure the module is enabled in **LSPosed** and the game is selected in **GAP**.
- If GAP cannot save changes, check that root access was granted.

---

## Notes

- GAP package name: `io.github.miner7222.gap`
- GAP is both the launcher app and the LSPosed settings entry for this module.
