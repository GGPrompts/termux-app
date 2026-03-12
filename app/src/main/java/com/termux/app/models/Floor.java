package com.termux.app.models;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a "floor" -- a named group of related terminal sessions.
 * The elevator/floor metaphor: each floor is a workspace context
 * (e.g. "Build", "Git", "Claude", "Server").
 */
public class Floor {

    private final String id;
    private String name;
    private final List<String> sessionNames;

    /** Default floors that are created on first launch. */
    public static final String FLOOR_DEFAULT = "General";
    public static final String FLOOR_BUILD = "Build";
    public static final String FLOOR_GIT = "Git";
    public static final String FLOOR_SERVER = "Server";

    public Floor(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.sessionNames = new ArrayList<>();
    }

    /**
     * Legacy constructor for backwards compatibility with saved prefs
     * that stored an icon string. The icon string is now ignored;
     * icon is determined by floor name via {@link #getIconResId()}.
     */
    public Floor(String name, String ignoredIcon) {
        this(name);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getSessionNames() {
        return sessionNames;
    }

    public void addSessionName(String sessionName) {
        if (!sessionNames.contains(sessionName)) {
            sessionNames.add(sessionName);
        }
    }

    public void removeSessionName(String sessionName) {
        sessionNames.remove(sessionName);
    }

    public int getSessionCount() {
        return sessionNames.size();
    }

    /** Returns a drawable resource ID for this floor's icon, based on the floor name. */
    public int getIconResId() {
        return iconResIdForName(name);
    }

    /** Maps a floor name to a vector drawable resource ID. */
    public static int iconResIdForName(String name) {
        if (name == null) return R.drawable.ic_floor_default;
        switch (name) {
            case FLOOR_DEFAULT: return R.drawable.ic_floor_general;
            case FLOOR_BUILD:   return R.drawable.ic_floor_build;
            case FLOOR_GIT:     return R.drawable.ic_floor_git;
            case FLOOR_SERVER:  return R.drawable.ic_floor_server;
            default:            return R.drawable.ic_floor_default;
        }
    }
}
