package com.service;

import java.util.List;

/**
 * Session-scoped user lookups for the admin console. The monolith no longer owns a user store
 * (identity lives in the auth-service), so this only reports who is currently logged in.
 */
public interface IUserService {

    List<String> getUsersFromSessionRegistry();

}
