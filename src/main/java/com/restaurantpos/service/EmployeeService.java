package com.restaurantpos.service;

import com.restaurantpos.dao.UserRepository;
import com.restaurantpos.enums.Role;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.SystemModels.User;
import com.restaurantpos.security.Access;
import com.restaurantpos.security.PasswordHasher;
import com.restaurantpos.security.Session;
import com.restaurantpos.util.Validators;

import java.util.List;

public final class EmployeeService {
    private final UserRepository users;
    private final AuditService audit;

    public EmployeeService(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    public List<User> all(Session session) {
        Access.requireManager(session);
        return users.all();
    }

    public User create(String username, String fullName, Role role, String tempPassword, boolean active, String photoPath,
                       Session session) {
        Access.requireManager(session);
        Validators.email(username, "Username (email)");
        Validators.required(fullName, "Full name");
        if (role == Role.OWNER && session.role() != Role.OWNER) throw new AppException("Only the owner can create another owner.");
        if (users.usernameExists(username)) throw new AppException("That username already exists.");
        AuthService.validatePassword(tempPassword, tempPassword);
        String salt = PasswordHasher.newSalt();
        long id = users.create(username.trim().toLowerCase(), PasswordHasher.hash(tempPassword, salt), salt,
                fullName.trim(), role, active, true, photoPath);
        audit.log("EMPLOYEE_CREATED", "users", id, fullName + " / " + role.label());
        return users.findById(id);
    }

    public void update(long id, String fullName, Role role, boolean active, String photoPath, Session session) {
        Access.requireManager(session);
        User existing = users.findById(id);
        if (existing == null) throw new AppException("Employee not found.");
        if (existing.role() == Role.OWNER && session.role() != Role.OWNER) throw new AppException("Only the owner can modify an owner account.");
        if (existing.id() == session.userId() && !active) throw new AppException("You cannot deactivate your own account.");
        users.update(id, Validators.required(fullName, "Full name"), role, active, photoPath);
        audit.log("EMPLOYEE_UPDATED", "users", id, fullName + " / " + role.label() + " / " + (active ? "Active" : "Inactive"));
    }

    public void resetPassword(long id, String newPassword, String confirm, Session session) {
        Access.requireManager(session);
        com.restaurantpos.service.AuthService.validatePassword(newPassword, confirm);
        String salt = PasswordHasher.newSalt();
        users.setPassword(id, PasswordHasher.hash(newPassword, salt), salt, true);
        audit.log("EMPLOYEE_PASSWORD_RESET", "users", id, "Temporary password set");
    }

    public void deactivate(long id, Session session) {
        Access.requireManager(session);
        User existing = users.findById(id);
        if (existing == null) throw new AppException("Employee not found.");
        if (existing.role() == Role.OWNER && session.role() != Role.OWNER) throw new AppException("Only the owner can deactivate an owner account.");
        if (existing.id() == session.userId()) throw new AppException("You cannot deactivate your own currently logged in account.");
        long activeOwners = users.all().stream().filter(u -> u.role() == Role.OWNER && u.active() && u.id() != id).count();
        if (existing.role() == Role.OWNER && activeOwners == 0) throw new AppException("Cannot deactivate the last active owner account.");
        users.update(id, existing.fullName(), existing.role(), false, existing.photoPath());
        audit.log("EMPLOYEE_DEACTIVATED", "users", id, existing.fullName());
    }

    public List<UserRepository.LoginAttempt> loginHistory(int limit, Session session) {
        Access.requireManager(session);
        return users.recentAttempts(limit);
    }
}
