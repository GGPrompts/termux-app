package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.models.Floor;
import com.termux.app.models.FloorManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import java.util.List;

/**
 * Adapter for the Floor list in the PocketForge navigation drawer.
 * Shows each floor with its icon, name, and session count.
 */
public class FloorListAdapter extends ArrayAdapter<Floor> {

    private final TermuxActivity mActivity;
    private final FloorManager mFloorManager;

    public FloorListAdapter(TermuxActivity activity, FloorManager floorManager) {
        super(activity.getApplicationContext(), R.layout.item_floor_list, floorManager.getFloors());
        this.mActivity = activity;
        this.mFloorManager = floorManager;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            view = inflater.inflate(R.layout.item_floor_list, parent, false);
        }

        Floor floor = getItem(position);
        if (floor == null) return view;

        TextView iconView = view.findViewById(R.id.floor_icon);
        TextView nameView = view.findViewById(R.id.floor_name);
        TextView countView = view.findViewById(R.id.floor_session_count);

        iconView.setText(floor.getIcon());
        nameView.setText(floor.getName());

        // Count sessions for this floor
        List<TermuxSession> allSessions = getAllSessions();
        int sessionCount = 0;
        if (allSessions != null) {
            sessionCount = mFloorManager.getSessionsForFloor(floor, allSessions).size();
        }

        if (sessionCount == 0) {
            countView.setText(mActivity.getString(R.string.drawer_floor_sessions_count_zero));
        } else if (sessionCount == 1) {
            countView.setText(mActivity.getString(R.string.drawer_floor_sessions_count_one));
        } else {
            countView.setText(mActivity.getString(R.string.drawer_floor_sessions_count, sessionCount));
        }

        // Highlight current floor
        boolean isCurrent = position == mFloorManager.getCurrentFloorIndex();
        view.setActivated(isCurrent);

        // Tint the icon for current floor
        if (isCurrent) {
            iconView.setTextColor(0xFF58A6FF); // pf_accent
            nameView.setTextColor(0xFFE6EDF3); // pf_text_primary
        } else {
            iconView.setTextColor(0xFF8B949E); // pf_text_secondary
            nameView.setTextColor(0xFF8B949E); // pf_text_secondary
        }

        return view;
    }

    private List<TermuxSession> getAllSessions() {
        if (mActivity.getTermuxService() != null) {
            return mActivity.getTermuxService().getTermuxSessions();
        }
        return null;
    }
}
