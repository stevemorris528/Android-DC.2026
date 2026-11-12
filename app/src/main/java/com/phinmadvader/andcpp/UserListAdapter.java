package com.phinmadvader.andcpp;

import java.util.ArrayList;
import java.util.List;

import com.phinmadvader.andcpp.DCPPService.FileSize;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.TextView;

public class UserListAdapter extends ArrayAdapter<DCUserComparable> {

	public interface OnUserMenuClickListener {
		void onUserMenuClick(View anchorView, DCUserComparable user);
	}

	private final Context context;
	private final List<DCUserComparable> nickList;
	private OnUserMenuClickListener menuClickListener;

	public UserListAdapter(Context context, int textViewResourceId,
			List<DCUserComparable> nickList) {
		super(context, textViewResourceId, nickList);
		this.context = context;
		this.nickList = nickList;
	}

	public void setOnUserMenuClickListener(OnUserMenuClickListener listener) {
		this.menuClickListener = listener;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		if (convertView == null) {
			LayoutInflater inflater = (LayoutInflater) context
					.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			convertView = inflater.inflate(R.layout.row_element, parent, false);
		}

		TextView title = (TextView) convertView.findViewById(R.id.text11);
		TextView support = (TextView) convertView.findViewById(R.id.text12);
		ImageView image = (ImageView) convertView.findViewById(R.id.icon);

		DCUserComparable user = getItem(position);
		if (user == null) return convertView;

		title.setText(user.nick);
		FileSize fs = new FileSize((double)user.share_size);
		support.setText("Share: "
				+ String.format("%.4g %s", fs.fileSize, fs.unit)  + "  " + (user.email != null ? user.email : ""));
		if (user.active)
			image.setImageResource(R.drawable.green_user);
		else
			image.setImageResource(R.drawable.red_user);

		View overflowMenu = convertView.findViewById(R.id.user_overflow_menu);
		if (overflowMenu != null) {
			overflowMenu.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (menuClickListener != null) {
						menuClickListener.onUserMenuClick(v, user);
					}
				}
			});
		}

		return convertView;
	}

	@Override
	public Filter getFilter() {
		return new Filter() {
			@Override
			protected FilterResults performFiltering(CharSequence constraint) {
				return new FilterResults();
			}
			@Override
			protected void publishResults(CharSequence constraint, FilterResults results) {}
		};
	}
}
