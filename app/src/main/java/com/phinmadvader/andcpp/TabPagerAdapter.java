package com.phinmadvader.andcpp;

import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.FragmentTransaction;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.Log;

public class TabPagerAdapter extends FragmentStatePagerAdapter implements
		TabListener, OnPageChangeListener {

	public final static int TAB_WELCOME = 0;
	public final static int TAB_LOGININFO = 1;
	public final static int TAB_HUBLIST = 2;
	public final static int TAB_HUBCHAT = 3;
	public final static int TAB_USERLIST = 4;
	public final static int TAB_SEARCHLIST = 5;
	public final static int TAB_DOWNLOADS = 6;
	public final static int TAB_TOTALS = 7;
	public final static int TAB_SAVED_FILELISTS = 8;
	public final static int TAB_FILELIST = 9;
	public final static int TAB_PRIVATECHAT = 10;
	private final static int TOTAL_TABS = 11;

	private boolean[] tab_is_visible;
	private int active_tab_count = 0;
	private Fragment[] tab_fragment;
	private Tab[] tab_list;
	private String[] tab_title;

	private ActionBar actionbar;
	private ViewPager viewpager;

	private FileListManager filelist_manager;
	private PrivateChatManager privatechat_manager;

	public TabPagerAdapter(FragmentManager fm, ActionBar actionbar,
			ViewPager viewpager, FileListManager flist_manager, PrivateChatManager pc_manager) {
		super(fm);
		this.actionbar = actionbar;
		this.viewpager = viewpager;
		this.filelist_manager = flist_manager;
		this.privatechat_manager = pc_manager;
		tab_is_visible = new boolean[TOTAL_TABS];
		tab_fragment = new Fragment[TOTAL_TABS];
		tab_list = new Tab[TOTAL_TABS];
		tab_title = new String[TOTAL_TABS];
		active_tab_count = 0;
		viewpager.setOnPageChangeListener(this);
		viewpager.setAdapter(this);
	}

	@Override
	public int getItemPosition(Object object) {
		// This ensures fragments don't get cached globally by position
		// As a result loss in performance, with fragments frequently re-rendered
		// Selectively return new position/POSITION_NONE based on shuffle/removal
		// or unchanged when no change
		return POSITION_NONE; // THIS IS HACK, :-/
	}

	public void hide_tab(int tabid) {
		hide_tab(tabid, true);
	}

	public void hide_tab(int tabid, boolean notify) {
		if (tab_is_visible[tabid]) {
			tab_is_visible[tabid] = false;
			tab_fragment[tabid] = null;
			active_tab_count--;
			boolean notified = false;
			if (tab_list[tabid] != null) {
				if (actionbar != null) {
					// Removing a tab makes the action bar select another one, which moves the pager.
					// The pager must already know the new page count, or it throws
					// "PagerAdapter changed the adapter's contents without calling notifyDataSetChanged".
					notifyDataSetChanged();
					notified = true;
					actionbar.removeTab(tab_list[tabid]);
				}
				tab_list[tabid] = null;
			}
			if (notify && !notified) notifyDataSetChanged();
		}
	}

	public void add_tab(int tabid, Fragment fragment, String title) {
		add_tab(tabid, fragment, title, true);
	}

	public void add_tab(int tabid, Fragment fragment, String title, boolean notify) {
		boolean tab_exists = tab_is_visible[tabid];
		// Update pager responsibilities
		tab_is_visible[tabid] = true;
		tab_fragment[tabid] = fragment;
		tab_title[tabid] = title;
		if (notify) notifyDataSetChanged();
		// Update Tab responsibilities (Must be AFTER Pager work)
		if (!tab_exists && actionbar != null) {
			int tabpos = 0;
			for (int i = 0; i < tabid; i++)
				if (tab_is_visible[i])
					tabpos++;
			
			if (tab_list[tabid] == null) {
				tab_list[tabid] = actionbar.newTab();
				if (tab_list[tabid] != null) {
					tab_list[tabid].setTabListener(this);
					active_tab_count++;
					actionbar.addTab(tab_list[tabid], tabpos, false);
				}
			}
		}
		if (tab_list[tabid] != null) {
			tab_list[tabid].setText(title);
			if (notify && actionbar != null) {
				try {
					actionbar.setSelectedNavigationItem(tab_list[tabid].getPosition());
				} catch (Exception e) {
					Log.e("andcpp", "Failed to set selected tab", e);
				}
			}
		}
	}

	@Override
	public int getCount() {
		int tab_count = 0;
		for (int i = 0; i < TOTAL_TABS; i++)
			if (tab_is_visible[i])
				tab_count++;
		if (tab_is_visible[TAB_FILELIST] && filelist_manager != null && filelist_manager.filelist_stack != null)
			tab_count += filelist_manager.getStackSize() - 1;
		if (tab_is_visible[TAB_PRIVATECHAT] && privatechat_manager != null)
			tab_count += privatechat_manager.getStackSize() - 1;
		// ^ -1 for 1 added in for loop because tab is visible
		return tab_count;
	}

	private int get_tabid(int position) {
		for (int i = 0; i < TOTAL_TABS; i++) {
			if (tab_is_visible[i]) {
				if (i == TAB_FILELIST) {
					int stackSize = (filelist_manager != null) ? filelist_manager.getStackSize() : 0;
					if (position < stackSize) {
						return TAB_FILELIST;
					} else {
						position -= stackSize;
					}
				} else if (i == TAB_PRIVATECHAT) {
					int stackSize = (privatechat_manager != null) ? privatechat_manager.getStackSize() : 0;
					if (position < stackSize) {
						return TAB_PRIVATECHAT;
					} else {
						position -= stackSize;
					}
				} else {
					position--;
					if (position < 0) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	@Override
	public Fragment getItem(int position) {
		int currentPos = 0;
		for (int i = 0; i < TOTAL_TABS; i++) {
			if (tab_is_visible[i]) {
				if (i == TAB_FILELIST) {
					int stackSize = (filelist_manager != null) ? filelist_manager.getStackSize() : 0;
					if (position >= currentPos && position < currentPos + stackSize) {
						return DirectoryViewFragment.newInstance(position - currentPos);
					}
					currentPos += stackSize;
				} else if (i == TAB_PRIVATECHAT) {
					int stackSize = (privatechat_manager != null) ? privatechat_manager.getStackSize() : 0;
					if (position >= currentPos && position < currentPos + stackSize) {
						return PrivateChatViewFragment.newInstance(position - currentPos);
					}
					currentPos += stackSize;
				} else {
					if (position == currentPos) {
						return tab_fragment[i];
					}
					currentPos++;
				}
			}
		}
		return null;
	}

	@Override
	public CharSequence getPageTitle(int position) {
		int tabid = get_tabid(position);
		if (tabid >= 0)
			return tab_title[tabid];
		return super.getPageTitle(position);
	}

	@Override
	public void onPageSelected(int position) {
		if (actionbar != null) {
			// Set tab to filelist; (last tab) if position out of range
			if (position >= active_tab_count)
				position = active_tab_count - 1;
			
			if (position >= 0 && position < actionbar.getTabCount()) {
				try {
					actionbar.setSelectedNavigationItem(position);
				} catch (Exception e) {
					Log.e("andcpp", "Error setting selected tab at position " + position, e);
				}
			}
		}
	}

	public Fragment get_tab_fragment(int tabid) {
		return tab_fragment[tabid];
	}

	public int get_tab_position(int tabid) {
		if (!tab_is_visible[tabid]) return -1;
		int pos = 0;
		for (int i = 0; i < tabid; i++) {
			if (tab_is_visible[i]) {
				if (i == TAB_FILELIST) {
					pos += (filelist_manager != null) ? filelist_manager.getStackSize() : 0;
				} else {
					pos++;
				}
			}
		}
		// If tabid itself is TAB_FILELIST, return the position of the first filelist page (or current stack index)
		if (tabid == TAB_FILELIST && filelist_manager != null && filelist_manager.filelist_stack != null && !filelist_manager.filelist_stack.isEmpty()) {
			pos += filelist_manager.filelist_stack.size() - 1;
		}
		return pos;
	}

	@Override
	public void onTabSelected(Tab tab, FragmentTransaction ft) {
		if (viewpager != null) {
			try {
				viewpager.setCurrentItem(tab.getPosition());
			} catch (IllegalStateException e) {
				// Page count changed without the pager being told yet: tell it, then retry once.
				Log.w("andcpp", "Pager out of sync on tab select; refreshing", e);
				notifyDataSetChanged();
				try {
					viewpager.setCurrentItem(tab.getPosition());
				} catch (IllegalStateException e2) {
					Log.e("andcpp", "Could not switch tab after refresh", e2);
				}
			}
		} else {
			Log.d("andcpp", "ViewPAGER NULL :-/ cannot switch tab on page");
		}
	}

	/**
	 * used on opening new directories of filelist
	 */
	public void move_to_last_page() {
		viewpager.setCurrentItem(getCount() - 1);
	}

	@Override
	public void onTabReselected(Tab tab, FragmentTransaction ft) {
	}

	@Override
	public void onTabUnselected(Tab tab, FragmentTransaction ft) {
	}

	@Override
	public void onPageScrollStateChanged(int arg0) {
	}

	@Override
	public void onPageScrolled(int arg0, float arg1, int arg2) {
	}
}
