package com.shoprearranger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ShopLayoutTest
{
	@Test
	public void noLayoutKeepsShopOrder()
	{
		assertArrayEquals(new int[]{0, 1, 2}, ShopLayout.assign(new int[]{10, 20, 30}, new HashMap<>(), 8));
	}

	@Test
	public void savedSlotsWinAndTheRestFillGaps()
	{
		Map<Integer, Integer> saved = new HashMap<>();
		saved.put(30, 0);
		saved.put(10, 7);
		// 30 takes slot 0, 10 takes slot 7, 20 has no slot and gets the lowest free one
		assertArrayEquals(new int[]{7, 1, 0}, ShopLayout.assign(new int[]{10, 20, 30}, saved, 8));
	}

	@Test
	public void pineappleToTopRight()
	{
		// Trader Stan's first two rows: the pineapple (index 15) swaps with the pot in the top right (index 7)
		Map<Integer, Integer> saved = new HashMap<>();
		for (int i = 0; i < 16; i++)
		{
			saved.put(100 + i, i);
		}
		saved.put(115, 7);
		saved.put(107, 15);
		int[] ids = new int[16];
		for (int i = 0; i < 16; i++)
		{
			ids[i] = 100 + i;
		}
		int[] slots = ShopLayout.assign(ids, saved, 16);
		assertEquals(7, slots[15]);
		assertEquals(15, slots[7]);
	}

	@Test
	public void outOfRangeOrDuplicateSlotsFallBack()
	{
		Map<Integer, Integer> saved = new HashMap<>();
		saved.put(10, 2);
		saved.put(20, 2);
		saved.put(30, 99);
		assertArrayEquals(new int[]{2, 0, 1}, ShopLayout.assign(new int[]{10, 20, 30}, saved, 3));
	}

	@Test
	public void insertForwardShiftsItemsBack()
	{
		// 10 dragged from slot 0 onto slot 3: 20, -, 40 shift back one, the gap moves with them
		Map<Integer, Integer> layout = ShopLayout.insert(new int[]{10, 20, -1, 40, 50}, 0, 3);
		assertEquals(Integer.valueOf(3), layout.get(10));
		assertEquals(Integer.valueOf(0), layout.get(20));
		assertEquals(Integer.valueOf(2), layout.get(40));
		assertEquals(Integer.valueOf(4), layout.get(50));
	}

	@Test
	public void insertBackwardShiftsItemsForward()
	{
		// The pineapple (115, slot 15) inserted at the top right (slot 7): slots 7-14 move up one
		int[] bySlot = new int[16];
		for (int i = 0; i < 16; i++)
		{
			bySlot[i] = 100 + i;
		}
		Map<Integer, Integer> layout = ShopLayout.insert(bySlot, 15, 7);
		assertEquals(Integer.valueOf(7), layout.get(115));
		assertEquals(Integer.valueOf(8), layout.get(107));
		assertEquals(Integer.valueOf(15), layout.get(114));
		assertEquals(Integer.valueOf(6), layout.get(106));
	}

	@Test
	public void encodeDecodeRoundTrip()
	{
		Map<Integer, Integer> layout = new LinkedHashMap<>();
		layout.put(2114, 7);
		layout.put(1963, 16);
		assertEquals(layout, ShopLayout.decode(ShopLayout.encode(layout)));
		assertEquals(layout, ShopLayout.decode("2114:7,bad,1963:16,x:y"));
		assertEquals(0, ShopLayout.decode(null).size());
	}

	@Test
	public void gridFromVanillaRows()
	{
		List<Point> vanilla = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			vanilla.add(new Point(10 + (i % 8) * 47, 4 + (i / 8) * 47));
		}
		ShopRearrangerPlugin.Grid grid = ShopRearrangerPlugin.Grid.from(vanilla, 400, 36);
		assertEquals(8, grid.cols);
		assertEquals(new Point(10 + 7 * 47, 4), grid.position(7));
		assertEquals(new Point(10 + 7 * 47, 4 + 3 * 47), grid.position(31));
	}
}
