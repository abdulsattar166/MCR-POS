package com.restaurantpos.service;

import com.restaurantpos.dao.SystemRepository;
import com.restaurantpos.dao.UserRepository;
import com.restaurantpos.dao.UserRepository.AuthRecord;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.security.PasswordHasher;
import com.restaurantpos.security.Session;

import java.time.LocalDateTime;

public final class AuthService {
    private final UserRepository users;
    private final SystemRepository system;
    private final Session session;

    public AuthService(UserRepository users, SystemRepository system, Session session) {
        this.users = users;
        this.system = system;
        this.session = session;
    }

    public User login(String username, String password) {
        if (username == null || username.isBlank()) throw new AppException("Enter your username.");
        if (password == null || password.isEmpty()) throw new AppException("Enter your password.");
        String uname = username.trim().toLowerCase();
        long failures = users.recentAttempts(30).stream()
                .filter(a -> a.username().equalsIgnoreCase(uname) && !a.success())
                .filter(a -> a.createdAt() != null && a.createdAt().isAfter(LocalDateTime.now().minusMinutes(10)))
                .count();
        if (failures >= 5) {
            users.logAttempt(uname, false, "Locked out after repeated failures");
            system.audit(null, uname, "LOGIN_BLOCKED", "users", null, "Too many failed attempts");
            throw new AppException("Too many failed attempts. Please wait 10 minutes before trying again.");
        }
        AuthRecord auth = users.findAuth(uname);
        if (auth == null) {
            users.logAttempt(uname, false, "Unknown username");
            system.audit(null, uname, "LOGIN_FAILED", "users", null, "Unknown username");
            throw new AppException("Invalid username or password.");
        }
        if (!auth.user().active()) {
            users.logAttempt(uname, false, "Inactive account");
            throw new AppException("This account is inactive. Contact the owner.");
        }
        if (!PasswordHasher.verify(password, auth.salt(), auth.hash())) {
            users.logAttempt(uname, false, "Incorrect password");
            system.audit(auth.user().id(), uname, "LOGIN_FAILED", "users", auth.user().id(), "Incorrect password");
            throw new AppException("Invalid username or password.");
        }
        users.logAttempt(uname, true, "Login successful");
        users.touchLogin(auth.user().id());
        session.start(auth.user());
        system.audit(auth.user().id(), auth.user().username(), "LOGIN", "users", auth.user().id(),
                "Signed in as " + auth.user().role().label());
        return auth.user();
    }

    public void logout() {
        if (session.active()) {
            system.audit(session.userId(), session.user().username(), "LOGOUT", "users", session.userId(), "Signed out");
        }
        session.end();
    }

    public void changeOwnPassword(String current, String newPassword, String confirm) {
        if (!session.active()) throw new AppException("Sign in to change your password.");
        AuthRecord auth = users.findAuth(session.user().username());
        if (auth == null || !PasswordHasher.verify(current, auth.salt(), auth.hash())) {
            throw new AppException("The current password is incorrect.");
        }
        validatePassword(newPassword, confirm);
        String salt = PasswordHasher.newSalt();
        users.setPassword(session.userId(), PasswordHasher.hash(newPassword, salt), salt, false);
        User refreshed = users.findById(session.userId());
        session.start(refreshed);
        system.audit(session.userId(), session.user().username(), "PASSWORD_CHANGED", "users", session.userId(),
                "Password changed by user");
    }

    public void resetPassword(long userId, String newPassword, String confirm) {
        if (!session.canManage()) throw new AppException("Only an owner or manager can reset passwords.");
        validatePassword(newPassword, confirm);
        String salt = PasswordHasher.newSalt();
        users.setPassword(userId, PasswordHasher.hash(newPassword, salt), salt, true);
        system.audit(session.userId(), session.username(), "PASSWORD_RESET", "users", userId,
                "Password reset by " + session.username());
    }

    public static void validatePassword(String password, String confirm) {
        if (password == null || password.length() < 8) throw new AppException("Password must be at least 8 characters.");
        if (!password.chars().anyMatch(Character::isLetter) || !password.chars().anyMatch(Character::isDigit)) {
            throw new AppException("Password must include letters and numbers.");
        }
        if (!password.equals(confirm)) throw new AppException("Passwords do not match.");
        if (password.equalsIgnoreCase("admin@1122") || password.equalsIgnoreCase("cashier@1122")) {
            throw new AppException("Choose a new password; the initial setup password cannot be reused.");
        }
    }

    public Session session() { return session; }
}
