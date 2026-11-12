package com.phinmadvader.andcpp;

import android.content.Context;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class MyNestedViewPager extends ViewPager {

	public MyNestedViewPager(Context context) {
		super(context);
	}

	public MyNestedViewPager(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent event) {
		try {
			return super.onInterceptTouchEvent(event);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		try {
			boolean handled = super.onTouchEvent(event);
			if (event.getAction() == MotionEvent.ACTION_UP) {
				performClick();
			}
			return handled;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Override
	public boolean performClick() {
		super.performClick();
		return true;
	}

	@Override
	protected boolean canScroll(View v, boolean checkV, int dx, int x, int y) {
		if (v != this && v instanceof ViewPager) {
			return ViewCompat.canScrollHorizontally(v, -dx);
		}
		return super.canScroll(v, checkV, dx, x, y);
	}
}


