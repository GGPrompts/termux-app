package com.termux.app.models;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages floor groups for PocketForge's navigation drawer.
 * Floors are persisted to SharedPreferences as JSON.
 * Sessions are assigned to floors by session name pattern matching
 * or explicit user assignment.
 */
public class FloorManager {

    private static final String LOG_TAG = "FloorManager";
    private static final String PREFS_NAME = "pocketforge_floors";
    private static final String KEY_FLOORS = "floors_json";
    private static final String KEY_CURRENT_FLOOR = "current_floor_index";

    private final Context mContext;
    private final List<Floor> mFloors;
    private int mCurrentFloorIndex;

    public FloorManager(Context context) {
        mContext = context;
        mFloors = new ArrayList<>();
        mCurrentFloorIndex = 0;
        load();
    }

    /** Returns all floors. */
    public List<Floor> getFloors() {
        return mFloors;
    }

    /** Returns the currently selected floor. */
    public Floor getCurrentFloor() {
        if (mFloors.isEmpty()) return null;
        if (mCurrentFloorIndex < 0 || mCurrentFloorIndex >= mFloors.size()) {
            mCurrentFloorIndex = 0;
        }
        return mFloors.get(mCurrentFloorIndex);
    }

    /** Returns the index of the currently selected floor. */
    public int getCurrentFloorIndex() {
        return mCurrentFloorIndex;
    }

    /** Sets the current floor by index. */
    public void setCurrentFloorIndex(int index) {
        if (index >= 0 && index < mFloors.size()) {
            mCurrentFloorIndex = index;
            save();
        }
    }

    /** Adds a new floor with the given name. */
    public Floor addFloor(String name) {
        Floor floor = new Floor(name);
        mFloors.add(floor);
        save();
        return floor;
    }

    /** Removes a floor by index. Cannot remove the last floor. */
    public boolean removeFloor(int index) {
        if (mFloors.size() <= 1 || index < 0 || index >= mFloors.size()) {
            return false;
        }
        mFloors.remove(index);
        if (mCurrentFloorIndex >= mFloors.size()) {
            mCurrentFloorIndex = mFloors.size() - 1;
        }
        save();
        return true;
    }

    /** Renames a floor. */
    public void renameFloor(int index, String newName) {
        if (index >= 0 && index < mFloors.size()) {
            mFloors.get(index).setName(newName);
            save();
        }
    }

    /**
     * Filters sessions that belong to the given floor.
     * A session belongs to a floor if:
     * 1. The session name is explicitly in the floor's sessionNames list, OR
     * 2. The floor is "General" (default) and the session is not assigned to any other floor
     */
    public List<TermuxSession> getSessionsForFloor(Floor floor, List<TermuxSession> allSessions) {
        if (floor == null || allSessions == null) {
            if (floor == null && allSessions != null) {
                Logger.logWarn(LOG_TAG, "getSessionsForFloor called with null floor, returning all sessions unfiltered");
            }
            return allSessions;
        }

        List<TermuxSession> result = new ArrayList<>();
        boolean isDefault = Floor.FLOOR_DEFAULT.equals(floor.getName());

        for (TermuxSession session : allSessions) {
            TerminalSession ts = session.getTerminalSession();
            if (ts == null) continue;

            String sessionName = ts.mSessionName;
            if (sessionName == null) sessionName = "";

            if (isDefault) {
                // Default floor gets all sessions not explicitly assigned elsewhere
                if (!isAssignedToAnyFloor(sessionName)) {
                    result.add(session);
                }
            } else {
                if (floor.getSessionNames().contains(sessionName)) {
                    result.add(session);
                }
            }
        }
        return result;
    }

    /**
     * Assigns a session to a floor by name. Removes from other floors first.
     */
    public void assignSessionToFloor(String sessionName, int floorIndex) {
        // Remove from all other floors
        for (Floor f : mFloors) {
            f.removeSessionName(sessionName);
        }
        // Assign to target floor (unless it's the default floor)
        if (floorIndex >= 0 && floorIndex < mFloors.size()) {
            Floor target = mFloors.get(floorIndex);
            if (!Floor.FLOOR_DEFAULT.equals(target.getName())) {
                target.addSessionName(sessionName);
            }
        }
        save();
    }

    /** Checks if a session name is explicitly assigned to any non-default floor. */
    private boolean isAssignedToAnyFloor(String sessionName) {
        for (Floor floor : mFloors) {
            if (Floor.FLOOR_DEFAULT.equals(floor.getName())) continue;
            if (floor.getSessionNames().contains(sessionName)) return true;
        }
        return false;
    }

    /** Load floors from SharedPreferences, or create defaults. */
    private void load() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_FLOORS, null);
        mCurrentFloorIndex = prefs.getInt(KEY_CURRENT_FLOOR, 0);

        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String name = obj.getString("name");
                    Floor floor = new Floor(name);

                    JSONArray sessions = obj.optJSONArray("sessions");
                    if (sessions != null) {
                        for (int j = 0; j < sessions.length(); j++) {
                            floor.addSessionName(sessions.getString(j));
                        }
                    }
                    mFloors.add(floor);
                }
            } catch (JSONException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to parse floors JSON", e);
                mFloors.clear();
            }
        }

        if (mFloors.isEmpty()) {
            createDefaultFloors();
        }

        if (mCurrentFloorIndex >= mFloors.size()) {
            mCurrentFloorIndex = 0;
        }
    }

    /** Persist floors to SharedPreferences. */
    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Floor floor : mFloors) {
                JSONObject obj = new JSONObject();
                obj.put("name", floor.getName());
                JSONArray sessions = new JSONArray();
                for (String s : floor.getSessionNames()) {
                    sessions.put(s);
                }
                obj.put("sessions", sessions);
                arr.put(obj);
            }

            SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putString(KEY_FLOORS, arr.toString())
                .putInt(KEY_CURRENT_FLOOR, mCurrentFloorIndex)
                .apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save floors JSON", e);
        }
    }

    /** Create the initial set of floors. */
    private void createDefaultFloors() {
        mFloors.add(new Floor(Floor.FLOOR_DEFAULT));
        mFloors.add(new Floor(Floor.FLOOR_BUILD));
        mFloors.add(new Floor(Floor.FLOOR_GIT));
        mFloors.add(new Floor(Floor.FLOOR_SERVER));
    }
}
