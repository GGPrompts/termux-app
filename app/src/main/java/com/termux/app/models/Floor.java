package com.termux.app.models;

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
    private String icon;
    private final List<String> sessionNames;

    /** Default floors that are created on first launch. */
    public static final String FLOOR_DEFAULT = "General";
    public static final String FLOOR_BUILD = "Build";
    public static final String FLOOR_GIT = "Git";
    public static final String FLOOR_SERVER = "Server";

    public Floor(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.icon = iconForName(name);
        this.sessionNames = new ArrayList<>();
    }

    public Floor(String name, String icon) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.icon = icon;
        this.sessionNames = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.icon = iconForName(name);
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
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

    /** Returns a Unicode icon character based on the floor name. */
    private static String iconForName(String name) {
        if (name == null) return "\u25A0"; // filled square
        switch (name) {
            case FLOOR_BUILD:   return "\u2692"; // hammer and pick
            case FLOOR_GIT:     return "\u2387"; // alternative key (branch-like)
            case FLOOR_SERVER:  return "\u2301"; // electric arrow
            case FLOOR_DEFAULT: return "\u25B6"; // right-pointing triangle
            default:            return "\u25A0"; // filled square
        }
    }
}
