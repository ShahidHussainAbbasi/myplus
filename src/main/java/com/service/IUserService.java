package com.service;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Optional;

import com.persistence.Repo.UserRepository;
import com.persistence.model.User;

public interface IUserService extends UserRepository{

//	User findById(Long id);

    User findByName(String name);

    void saveUser(User user);

    void updateUser(User user);

    void deleteUserById(Long id);

    void deleteAllUsers();

    List<User> findAllUsers();

    boolean isUserExist(User user);

    void deleteUser(User user);

    User findUserByEmail(String email);

    Optional<User> getUserByID(long id);

    void changeUserPassword(User user, String password);

    boolean checkIfValidOldPassword(User user, String password);

    String generateQRUrl(User user) throws UnsupportedEncodingException;

    User updateUser2FA(boolean use2FA);

    List<String> getUsersFromSessionRegistry();
    
    List<String> getUsersIdFromSessionRegistry();

}
