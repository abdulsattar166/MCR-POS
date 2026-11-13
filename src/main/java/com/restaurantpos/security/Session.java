package com.restaurantpos.security;

import com.restaurantpos.enums.Role;
import com.restaurantpos.model.SystemModels.User;

import java.time.LocalDateTime;

public final class Session {
    private User user;
    private LocalDateTime loginAt;
    private LocalDateTime lastActivity;

    public void start(User user) {
        this.user = user;
        this.loginAt = LocalDateTime.now();
        this.lastActivity = this.loginAt;
    }

    public void touch() { lastActivity = LocalDateTime.now(); }
    public void end() { user = null; }
    public boolean active() { return user != null; }
    public User user() { return user; }
    public Long userId() { return user == null ? null : user.id(); }
    public String username() { return user == null ? "system" : user.fullName(); }
    public Role role() { return user == null ? null : user.role(); }
    public LocalDateTime loginAt() { return loginAt; }
    public LocalDateTime lastActivity() { return lastActivity; }
    public boolean idleMinutesExceeded(int minutes) {
        return active() && lastActivity != null && lastActivity.plusMinutes(minutes).isBefore(LocalDateTime.now());
    }
    public boolean isOwner() { return role() == Role.OWNER; }
    public boolean canManage() { return role() == Role.OWNER || role() == Role.MANAGER; }
}
