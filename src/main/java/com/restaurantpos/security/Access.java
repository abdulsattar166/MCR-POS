package com.restaurantpos.security;

import com.restaurantpos.enums.Role;
import com.restaurantpos.exception.AppException;
import com.restaurantpos.model.SystemModels.User;

import java.util.Arrays;

public final class Access {
    private Access() {}

    public static User require(Session session, Role... permitted) {
        if (session == null || !session.active()) throw new AppException("Your session has ended. Please sign in again.");
        if (permitted.length == 0) return session.user();
        if (Arrays.stream(permitted).noneMatch(r -> r == session.role())) {
            throw new AppException("Your role does not have permission to perform this action.");
        }
        return session.user();
    }

    public static User requireManager(Session session) {
        return require(session, Role.OWNER, Role.MANAGER);
    }
}
