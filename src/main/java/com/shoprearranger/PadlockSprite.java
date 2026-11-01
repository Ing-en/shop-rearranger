package com.shoprearranger;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.game.SpriteOverride;

/** The padlock drawn in the shop's top-left corner. Custom ids, well clear of the cache's own sprites. */
@Getter
@RequiredArgsConstructor
enum PadlockSprite implements SpriteOverride
{
	LOCKED(-30201, "locked.png"),
	UNLOCKED(-30202, "unlocked.png");

	private final int spriteId;
	private final String fileName;
}
