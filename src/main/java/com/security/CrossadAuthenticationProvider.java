package com.security;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.token.TokenService;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;


public class CrossadAuthenticationProvider implements AuthenticationProvider {

	    private Logger logger = LoggerFactory.getLogger(this.getClass());
	    
	    /** 
	     * When authentication details contains this constant, it looks for
	     * the data directly in DB, not in the cache. The result is then put
	     * to the cache if the cache is set in configuration.
	     * Useful during login, user has set actual roles, not cached.
	     */
	    public static final String FRESH_DATA = "fresh_data";

	    @Override
	    public boolean supports(Class<? extends Object> authentication) {
	        return authentication.equals(UsernamePasswordAuthenticationToken.class);
	    }

	    @Override
	    public Authentication authenticate(Authentication authentication) {
	        if (authentication instanceof RememberMeAuthenticationToken) {
	            return authenticateByToken((RememberMeAuthenticationToken) authentication);
	        }
	        
	        if (authentication instanceof PreAuthenticatedAuthenticationToken) {
	            return spnegoAuthentication((PreAuthenticatedAuthenticationToken) authentication);
	        }

	        if (!(authentication instanceof UsernamePasswordAuthenticationToken)) {
	            return null;
	        }
	        if (authentication.getCredentials() == null || authentication.getPrincipal() == null) {
//	            throw new TranslatableMissingCredentialsException();
	        }

	        // In CrossAd, all usernames are in uppercase.
	        String username = authentication.getPrincipal().toString().toUpperCase();
	        String password = authentication.getCredentials().toString();
	        boolean freshData = FRESH_DATA.equals(authentication.getDetails()); 
	        logger.debug("Authenticating user \"{}\" by password", username);
//	        AuthenticationResult result = crossadUserDao.authenticate(username, password, freshData);
//
//	        if (result.isOk() && StringUtils.equalsIgnoreCase(password, UserDao.DEFAULT_PASSWORD__MPRESS)) {
//	            throw new TranslatablePasswordExpiredException();
//	        }
//
//	        if (!result.isOk()) {
//	            final int errorCode = result.getErrorCode();
//	            logger.warn("Failed to login; error code={}", errorCode);
//	            if (errorCode == OracleErrorCodes.ORA_01017_WRONG_CREDENTIALS) {
//	                throw new TranslatableBadCredentialsException();
//	            } else if (errorCode == OracleErrorCodes.ORA_28001_PASSWORD_HAS_EXPIRED) {
//	                throw new TranslatablePasswordExpiredException();
//	            } else if (errorCode == OracleErrorCodes.ORA_28000_ACCOUNT_IS_LOCKED) {
//	                throw new TranslatableAccountIsLockedException();
//	            } else {
//	                throw new TranslatablePasswordExpiredException();
//	            }
//	        }

	        notifyWarnings(null/*result.getWarnings()*/);

	        List<String> roles = null;// result.getRoles();
	        return buildAuthenticationToken(username, password, roles);
	    }

	    /**
	     * NOTE: The specified token is expected to be already validated using
	     * {@link TokenService#verifyToken(String)} and checked for session expiration.
	     * 
	     * @param token
	     * @return
	     */
	    private Authentication authenticateByToken(RememberMeAuthenticationToken token) {
	        String loginName = token.getName();
	        logger.debug("Authenticating user \"{}\" by token", loginName);
//	        List<String> roles = crossadUserDao.getRolesByUser(loginName, false);
//	        return buildAuthenticationToken(loginName, "keyHash:" + token.getKeyHash(), roles);
	        return null;
	    }

	    private Authentication spnegoAuthentication(PreAuthenticatedAuthenticationToken token) {
	        String loginName = token.getName();
	        String loginNameNormalized = normalizeLoginName(loginName);
	        logger.info("Authenticating user \"{}\" by token", loginNameNormalized);
//	        List<String> roles = crossadUserDao.getRolesByUser(loginNameNormalized, false);
//	        Authentication authenticated = buildAuthenticationToken(loginNameNormalized, "__hidden__", roles);
//	        return authenticated;
	        return null;
	    }

	    private String normalizeLoginName(String loginName) {
	        if (loginName == null) return null;
	        return loginName.trim().toUpperCase();
	    }

	    private Authentication buildAuthenticationToken(String loginName, String password, List<String> roles) {
	        logger.trace("Found roles: {}", roles);

//	        FUser fUser = userDao.findByUserLogin(loginName);
//	        if (fUser == null) {
//	            throw new UsernameNotFoundException(loginName);
//	        }
//	        if (BooleanUtil.isTrue(fUser.getUsExpired())) {
//	            throw new AccountExpiredException(loginName);
//	        }

	        String userGroup = null;
//	        if (fUser.getFUsergroup() != null) {
//	            userGroup = fUser.getFUsergroup().getUgUgcode();
//	        }

	        List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
	        authorities.add(new SimpleGrantedAuthority("ROLE_USER_AUTHENTICATED"));
	        for (String role : roles) {
	            authorities.add(new SimpleGrantedAuthority(role));
	        }
	        logger.trace("Returning authorities {}", authorities);
//	        UserDetails user = new CrossadUser(loginName, fUser.getUsUser(), password, fUser.getUsEmailaddr(), true, true, true, true,
//	                authorities, userGroup);

	        return new UsernamePasswordAuthenticationToken(null/*user*/, password, authorities);
	    }

	    private void notifyWarnings(SQLWarning warning) {
	        while (warning != null)
	        {
//	            CoreMessage coreMessage = null;
//	            switch (warning.getErrorCode()) {
//	                case 28002:
//	                    coreMessage = new CoreMessage("auth.db.password.expires.soon");
//	                    break;
//	                default:
//	                    coreMessage = new CoreMessage("auth.db.oracode", warning.getErrorCode(), warning.getMessage());
//	            }
//
//	            messageService.warn(coreMessage);
	            warning = warning.getNextWarning();
	        }
	    }
}
