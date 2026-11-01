package com.shoprearranger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A shop's saved arrangement: which grid slot each item id sits in. Items are matched by id rather than by
 * stock position, so the layout survives the shop gaining or losing stock.
 */
final class ShopLayout
{
	private ShopLayout()
	{
	}

	/**
	 * Works out the slot for each item. Items with a saved slot go there first; everything else (new stock,
	 * or an item whose saved slot no longer exists or is already used) fills the lowest free slots in the
	 * shop's own order.
	 *
	 * @param itemIds   the shop's items in vanilla order
	 * @param saved     item id to slot
	 * @param slotCount slots in the grid, at least {@code itemIds.length}
	 * @return the slot for each entry of {@code itemIds}
	 */
	static int[] assign(int[] itemIds, Map<Integer, Integer> saved, int slotCount)
	{
		int[] slots = new int[itemIds.length];
		Arrays.fill(slots, -1);
		boolean[] taken = new boolean[slotCount];

		for (int i = 0; i < itemIds.length; i++)
		{
			Integer slot = saved.get(itemIds[i]);
			if (slot != null && slot >= 0 && slot < slotCount && !taken[slot])
			{
				slots[i] = slot;
				taken[slot] = true;
			}
		}

		int next = 0;
		for (int i = 0; i < itemIds.length; i++)
		{
			if (slots[i] != -1)
			{
				continue;
			}
			while (taken[next])
			{
				next++;
			}
			slots[i] = next;
			taken[next] = true;
		}
		return slots;
	}

	/**
	 * Moves the item in slot {@code from} to slot {@code to}, shifting everything in between one slot back toward
	 * {@code from}, the way the bank's Insert mode does. Empty slots in between shift along too.
	 *
	 * @param bySlot item id in each slot, -1 for empty
	 * @return item id to slot after the move
	 */
	static Map<Integer, Integer> insert(int[] bySlot, int from, int to)
	{
		int[] next = bySlot.clone();
		int moving = next[from];
		if (from < to)
		{
			System.arraycopy(next, from + 1, next, from, to - from);
		}
		else if (from > to)
		{
			System.arraycopy(next, to, next, to + 1, from - to);
		}
		next[to] = moving;

		Map<Integer, Integer> layout = new LinkedHashMap<>();
		for (int slot = 0; slot < next.length; slot++)
		{
			if (next[slot] > 0)
			{
				layout.putIfAbsent(next[slot], slot);
			}
		}
		return layout;
	}

	/** {@code "itemId:slot,itemId:slot"} */
	static String encode(Map<Integer, Integer> layout)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> e : layout.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	static Map<Integer, Integer> decode(String value)
	{
		Map<Integer, Integer> layout = new LinkedHashMap<>();
		if (value == null || value.isEmpty())
		{
			return layout;
		}
		for (String pair : value.split(","))
		{
			String[] parts = pair.split(":");
			if (parts.length != 2)
			{
				continue;
			}
			try
			{
				layout.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
			}
			catch (NumberFormatException ignored)
			{
				// Skip a damaged entry rather than losing the whole layout
			}
		}
		return layout;
	}
}
