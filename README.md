# DuckyPVP

Private QDucks SMP PvP arena controller for Paper 1.21.11.

DuckyPVP owns only the arena gameplay loop:
- random kit rotation
- isolated player kit inventories while inside the arena
- kit item protection: kit items are tagged and can't be dropped, stored, crafted with, sold or picked up outside the arena (`kit-protection` in config.yml)
- 15-minute arena resets
- restoration of player-placed/broken/exploded blocks

WorldGuard remains responsible for protecting the permanent arena floor, walls, spawn, and other server regions.

Development happens on the `dev` branch.
