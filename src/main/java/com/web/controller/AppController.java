//package com.web.controller;
//
//import javax.servlet.http.HttpServletRequest;
//
//import com.service.UserService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.MessageSource;
//import org.springframework.security.authentication.AuthenticationTrustResolver;
//import org.springframework.security.core.Authentication;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
//import org.springframework.stereotype.Controller;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.SessionAttributes;
//
//
//@Controller
//@RequestMapping("/")
//@SessionAttributes("roles")
//public class AppController {
//
//	@Autowired
//	UserService userService;
//	
////	@Autowired
////	UserProfileService userProfileService;
//	
//	@Autowired
//	MessageSource messageSource;
//
//	@Autowired
//	PersistentTokenBasedRememberMeServices persistentTokenBasedRememberMeServices;
//	
//	@Autowired
//	AuthenticationTrustResolver authenticationTrustResolver;
//	
////	
////	/**
////	 * This method will list all existing users.
////	 */
////	@RequestMapping(value = {"/list" }, method = RequestMethod.GET)
////	public String listUsers(ModelMap model) {
////
////		model.addAttribute("loggedinuser", getPrincipal());
////		List<User> users = userService.findAllUsers();
////		model.addAttribute("users", users);
////		return "userslist";
////	}
////
////	/**
////	 * This method handles login GET requests.
////	 * If users is already logged-in and tries to goto login page again, will be redirected to list page.
////	 */
////	@RequestMapping(value ={"/", "/login"}, method = RequestMethod.GET)
////	public String loginPage() {
////	    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
////		if (authenticationTrustResolver.isAnonymous(authentication)) {
////			return "login";
////	    } else {
//////	    	if(true)
////	    	return "redirect:/dashboard";  
////	    }
////	}
////
////	/**
////	 * This method will show the relevant user's dashboard.
////	 */
////	@RequestMapping(value = {"/dashboard" }, method = RequestMethod.GET)
////	public String dashboard(ModelMap model) {
////		model.addAttribute("loggedinuser", getPrincipal());
////		String role = this.getRole();
////		if(role.equals("ROLE_USER") || role.equals("ROLE_SUB")){
////			return "dashboard";
////		}else if(!role.equals("ROLE_SUB") && !role.equals("ROLE_USER")){
////			List<User> users = userService.findAllUsers();
////			model.addAttribute("users", users);
////			return "userslist";
////		}else{
////			return "accessDenied";
////		}
////	}
////
////	/**
////	 * This method will provide the medium to add a new user.
////	 */
////	@RequestMapping(value = { "/newuser" }, method = RequestMethod.GET)
////	public String newUser(ModelMap model) {
////		User user = new User();
////		model.addAttribute("user", user);
////		model.addAttribute("edit", false);
////		model.addAttribute("loggedinuser", getPrincipal());
////		return "registration";
////	}
////
////	/**
////	 * This method will be called on form submission, handling POST request for
////	 * saving user in database. It also validates the user input
////	 */
////	@RequestMapping(value = { "/newuser" }, method = RequestMethod.POST)
////	public String saveUser(@Valid User user, BindingResult result,
////			ModelMap model) {
////
////		if (result.hasErrors()) {
////			return "registration";
////		}
////
////		/*`
////		 * Preferred way to achieve uniqueness of field [sso] should be implementing custom @Unique annotation 
////		 * and applying it on field [sso] of Model class [User].
////		 * 
////		 * Below mentioned peace of code [if block] is to demonstrate that you can fill custom errors outside the validation
////		 * framework as well while still using internationalized messages.
////		 * 
////		 */
////		if(!userService.isUserSSOUnique(user.getId(), user.getSsoId())){
////			FieldError ssoError =new FieldError("user","ssoId",messageSource.getMessage("non.unique.ssoId", new String[]{user.getSsoId()}, Locale.getDefault()));
////		    result.addError(ssoError);
////			return "registration";
////		}
////		if(!getRole().equals("ROLE_SUB"))
////			user.setParent(getRole());
////		
////		userService.saveUser(user);
////
////		model.addAttribute("success", "User " + user.getFirstName() + " "+ user.getLastName() + " registered successfully");
////		model.addAttribute("loggedinuser", getRole());
////		//return "success";
////		return "registrationsuccess";
////	}
////
////
////	/**
////	 * This method will provide the medium to update an existing user.
////	 */
////	@RequestMapping(value = { "/edit-user-{ssoId}" }, method = RequestMethod.GET)
////	public String editUser(@PathVariable String ssoId, ModelMap model) {
////		User user = userService.findBySSO(ssoId);
////		model.addAttribute("user", user);
////		model.addAttribute("edit", true);
////		model.addAttribute("loggedinuser", getPrincipal());
////		return "registration";
////	}
////	
////	/**
////	 * This method will be called on form submission, handling POST request for
////	 * updating user in database. It also validates the user input
////	 */
////	@RequestMapping(value = { "/edit-user-{ssoId}" }, method = RequestMethod.POST)
////	public String updateUser(@Valid User user, BindingResult result,
////			ModelMap model, @PathVariable String ssoId) {
////
////		if (result.hasErrors()) {
////			return "registration";
////		}
////
////		/*//Uncomment below 'if block' if you WANT TO ALLOW UPDATING SSO_ID in UI which is a unique key to a User.
////		if(!userService.isUserSSOUnique(user.getId(), user.getSsoId())){
////			FieldError ssoError =new FieldError("user","ssoId",messageSource.getMessage("non.unique.ssoId", new String[]{user.getSsoId()}, Locale.getDefault()));
////		    result.addError(ssoError);
////			return "registration";
////		}*/
////
////
////		userService.updateUser(user);
////
////		model.addAttribute("success", "User " + user.getFirstName() + " "+ user.getLastName() + " updated successfully");
////		model.addAttribute("loggedinuser", getPrincipal());
////		return "registrationsuccess";
////	}
////
////	
////	/**
////	 * This method will delete an user by it's SSOID value.
////	 */
////	@RequestMapping(value = { "/delete-user-{ssoId}" }, method = RequestMethod.GET)
////	public String deleteUser(@PathVariable String ssoId) {
////		userService.deleteUserBySSO(ssoId);
////		return "redirect:/list";
////	}
////	
////
////	/**
////	 * This method will provide UserProfile list to views
////	 */
////	@ModelAttribute("roles")
////	public List<UserProfile> initializeProfiles() {
////		return userProfileService.findAll();
////	}
////	
////	/**
////	 * This method handles Access-Denied redirect.
////	 */
////	@RequestMapping(value = "/Access_Denied", method = RequestMethod.GET)
////	public String accessDeniedPage(ModelMap model) {
////		model.addAttribute("loggedinuser", getPrincipal());
////		return "accessDenied";
////	}
////
////	/**
////	 * This method handles logout requests.
////	 * Toggle the handlers if you are RememberMe functionality is useless in your app.
////	 */
////	@RequestMapping(value="/logout", method = RequestMethod.GET)
////	public String logoutPage (HttpServletRequest request, HttpServletResponse response){
////		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
////		if (auth != null){    
////			//new SecurityContextLogoutHandler().logout(request, response, auth);
////			persistentTokenBasedRememberMeServices.logout(request, response, auth);
////			SecurityContextHolder.getContext().setAuthentication(null);
////		}
////		return "redirect:/login?logout";
////	}
//
//	/**
//	 * This method returns the principal[user-name] of logged-in user.
//	 */
//	public String getPrincipal(){
//		String userName = null;
//		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
//
//		if (principal instanceof UserDetails) {
//			userName = ((UserDetails)principal).getUsername();
//		} else {
//			userName = principal.toString();
//		}
//		return userName;
//	}
//	
//	public String getRole(){
//		String role = null;
//		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
//
//		if (principal instanceof UserDetails) {
//				role = ((org.springframework.security.core.userdetails.User) principal).getAuthorities().iterator().next().getAuthority().toString();
//		} else {
//			role = principal.toString();
//		}
//		return role;
//	}
//	
//	public String getAppUrl(HttpServletRequest request) {
//		return "http://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
//	}
//	
//	/**
//	 * This method returns true if users is already authenticated [logged-in], else false.
//	 */
//	public boolean isCurrentAuthenticationAnonymous() {
//	    final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//	    return authenticationTrustResolver.isAnonymous(authentication);
//	}
//
//
//}