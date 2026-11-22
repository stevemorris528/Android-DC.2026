package com.phinmadvader.andcpp;

import java.util.Comparator;

import android.util.Log;

import com.phinvader.libjdcpp.DCUser;

public class DCUserComparable extends DCUser implements Comparable<DCUser>{

	@Override
	public int compareTo(DCUser another) {
		if (another == null || another.nick == null) return 1;
		if (this.nick == null) return -1;
		if (this.op != another.op) return this.op ? -1 : 1; // operators always at the top
		return this.nick.compareToIgnoreCase(another.nick);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		DCUserComparable that = (DCUserComparable) o;
		return nick != null ? nick.equalsIgnoreCase(that.nick) : that.nick == null;
	}

	@Override
	public int hashCode() {
		return nick != null ? nick.toLowerCase().hashCode() : 0;
	}
	
	public DCUserComparable(DCUser dcuser){
		super(dcuser);
	}
	
	public DCUserComparable(String nick){
		// DUmmy Constructor, only to be used for delete
		this.nick = nick;
		
	}
	
	
	public static class ShareSizeCompare implements Comparator<DCUserComparable>{

		@Override
		public int compare(DCUserComparable arg0, DCUserComparable arg1) {
			if (arg0.op != arg1.op) return arg0.op ? -1 : 1; // operators always at the top
			return (int)arg0.share_size - (int)arg1.share_size;
		}
		
	}

}
