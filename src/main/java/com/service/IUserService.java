package com.service;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Optional;

import com.persistence.Repo.UserRepository;
import com.persistence.model.PasswordResetToken;
import com.persistence.model.User;
import com.persistence.model.VerificationToken;
import com.web.dto.UserDto;
import com.web.error.UserAlreadyExistException;

public interface IUserService extends UserRepository{

//	User findById(Long id);
	 
    User findByName(String name);
 
    void saveUser(User user);
 
    void updateUser(User user);
 
    void deleteUserById(Long id);
 
    void deleteAllUsers();
 
    List<User> findAllUsers();
 
    boolean isUserExist(User user);
    
	User registerNewUserAccount(UserDto accountDto) throws UserAlreadyExistException;

    User getUser(String verificationToken);

    void saveRegisteredUser(User user);

    void deleteUser(User user);

    void createVerificationTokenForUser(User user, String token);

    VerificationToken getVerificationToken(String VerificationToken);

    VerificationToken generateNewVerificationToken(String token);

    void createPasswordResetTokenForUser(User user, String token);

    User findUserByEmail(String email);

    PasswordResetToken getPasswordResetToken(String token);

    User getUserByPasswordResetToken(String token);

    Optional<User> getUserByID(long id);

    void changeUserPassword(User user, String password);

    boolean checkIfValidOldPassword(User user, String password);

    String validateVerificationToken(String token);

    String generateQRUrl(User user) throws UnsupportedEncodingException;

    User updateUser2FA(boolean use2FA);

    List<String> getUsersFromSessionRegistry();
    
    List<String> getUsersIdFromSessionRegistry();

}
